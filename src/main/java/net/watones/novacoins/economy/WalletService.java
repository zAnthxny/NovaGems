package net.watones.novacoins.economy;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.watones.novacoins.storage.DurableMutationResult;
import net.watones.novacoins.storage.CorruptRecoveryRecord;
import net.watones.novacoins.storage.JournalHealth;
import net.watones.novacoins.storage.JournalWriter;
import net.watones.novacoins.storage.RecoveryJournal;
import net.watones.novacoins.storage.StorageHealth;
import net.watones.novacoins.storage.StorageProvider;

/**
 * Ordered per-account mutations on a bounded shared executor. RAM is updated only after SQL commit.
 */
public final class WalletService implements AutoCloseable {
  private static final long ERROR_LOG_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
  private static final int MAX_LEADERBOARD_CACHE_ENTRIES = 128;
  private final StorageProvider storage;
  private final JournalWriter journal;
  private final Consumer<Throwable> persistenceError;
  private final Map<UUID, AccountHandle> handles = new ConcurrentHashMap<>();
  private final Map<UUID, EconomyOperation> pendingDeliveries = new ConcurrentHashMap<>();
  private final Map<UUID, EconomyOperation> pendingPurchases = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> pendingPurchaseCounts = new ConcurrentHashMap<>();
  private final Map<UUID, TransactionStatus> deliveryStatuses = new ConcurrentHashMap<>();
  private final java.util.Set<UUID> activeDeliveries = ConcurrentHashMap.newKeySet();
  private final Map<LeaderboardKey, CacheEntry> leaderboardCache = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicLong> accountSequences = new ConcurrentHashMap<>();
  private final Map<UUID, Long> assignedSequences = new ConcurrentHashMap<>();
  private final PendingAdministrativeOperations pendingAdministrativeOperations;
  private final ThreadPoolExecutor io;
  private final ScheduledThreadPoolExecutor recoveryScheduler;
  private final int perPlayerQueueLimit;
  private final int globalMutationLimit;
  private final int recoveryBatchSize;
  private final int recoveryBaseIntervalSeconds;
  private final int recoveryMaxBackoffSeconds;
  private final long leaderboardCacheNanos;
  private volatile int journalDrainTimeoutSeconds;
  private volatile int databaseDrainTimeoutSeconds;
  private final Object drainMonitor = new Object();
  private final AtomicInteger pendingMutations = new AtomicInteger();
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicBoolean recoveryRunning = new AtomicBoolean();
  private final AtomicBoolean fastRecoveryScheduled = new AtomicBoolean();
  private final AtomicLong lastErrorLog = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastAdministrativePendingLog = new AtomicLong(Long.MIN_VALUE);
  private final LongAdder submitted = new LongAdder();
  private final LongAdder committed = new LongAdder();
  private final LongAdder duplicates = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder recoveryProcessed = new LongAdder();
  private final LongAdder recoveryFailed = new LongAdder();
  private final AtomicLong nextRecoveryNanos = new AtomicLong();
  private final AtomicLong nextJournalReconcileNanos =
      new AtomicLong(System.nanoTime() + TimeUnit.MINUTES.toNanos(10));
  private final AtomicLong manualReviewCount = new AtomicLong();
  private final AtomicInteger recoveryBackoffSeconds = new AtomicInteger();
  private volatile StorageHealth health = StorageHealth.HEALTHY;
  private volatile boolean accepting = true;
  private volatile Consumer<UUID> deliveryReady = ignored -> {};
  private volatile Consumer<UUID> recoveredRewardReady = ignored -> {};
  private volatile Consumer<UUID> manualReviewCreated = ignored -> {};
  private volatile Consumer<String> debugLog = ignored -> {};
  private volatile Consumer<String> administrativeLog = ignored -> {};
  private final CompletableFuture<Void> administrativeRegistryReady = new CompletableFuture<>();

  public WalletService(StorageProvider storage, Consumer<Throwable> persistenceError) {
    this(
        storage,
        Path.of("plugins", "NovaCoins", "recovery"),
        persistenceError,
        1024,
        64,
        4096,
        30,
        5,
        4096,
        250,
        30,
        300);
  }

  public WalletService(
      StorageProvider storage,
      Path recoveryDirectory,
      Consumer<Throwable> persistenceError,
      int executorQueueLimit,
      int perPlayerQueueLimit,
      int globalMutationLimit) {
    this(
        storage,
        recoveryDirectory,
        persistenceError,
        executorQueueLimit,
        perPlayerQueueLimit,
        globalMutationLimit,
        30,
        5,
        4096,
        250,
        30,
        300);
  }

  public WalletService(
      StorageProvider storage,
      Path recoveryDirectory,
      Consumer<Throwable> persistenceError,
      int executorQueueLimit,
      int perPlayerQueueLimit,
      int globalMutationLimit,
      int leaderboardCacheSeconds) {
    this(
        storage,
        recoveryDirectory,
        persistenceError,
        executorQueueLimit,
        perPlayerQueueLimit,
        globalMutationLimit,
        leaderboardCacheSeconds,
        5,
        4096,
        250,
        30,
        300);
  }

  public WalletService(
      StorageProvider storage,
      Path recoveryDirectory,
      Consumer<Throwable> persistenceError,
      int executorQueueLimit,
      int perPlayerQueueLimit,
      int globalMutationLimit,
      int leaderboardCacheSeconds,
      int shutdownDrainTimeoutSeconds) {
    this(storage, recoveryDirectory, persistenceError, executorQueueLimit, perPlayerQueueLimit,
        globalMutationLimit, leaderboardCacheSeconds, shutdownDrainTimeoutSeconds,
        4096, 250, 30, 300);
  }

  public WalletService(
      StorageProvider storage,
      Path recoveryDirectory,
      Consumer<Throwable> persistenceError,
      int executorQueueLimit,
      int perPlayerQueueLimit,
      int globalMutationLimit,
      int leaderboardCacheSeconds,
      int shutdownDrainTimeoutSeconds,
      int journalQueueCapacity,
      int recoveryBatchSize,
      int recoveryBaseIntervalSeconds,
      int recoveryMaxBackoffSeconds) {
    this(storage, recoveryDirectory, persistenceError, executorQueueLimit, perPlayerQueueLimit,
        globalMutationLimit, leaderboardCacheSeconds, shutdownDrainTimeoutSeconds,
        journalQueueCapacity, recoveryBatchSize, recoveryBaseIntervalSeconds,
        recoveryMaxBackoffSeconds, new RecoveryJournal(recoveryDirectory));
  }

  public WalletService(
      StorageProvider storage,
      Path recoveryDirectory,
      Consumer<Throwable> persistenceError,
      int executorQueueLimit,
      int perPlayerQueueLimit,
      int globalMutationLimit,
      int leaderboardCacheSeconds,
      int shutdownDrainTimeoutSeconds,
      int journalQueueCapacity,
      int recoveryBatchSize,
      int recoveryBaseIntervalSeconds,
      int recoveryMaxBackoffSeconds,
      RecoveryJournal journalPrimitive) {
    if (executorQueueLimit < 1 || perPlayerQueueLimit < 1 || globalMutationLimit < 1) {
      throw new IllegalArgumentException("Queue limits must be positive");
    }
    if (journalQueueCapacity < 1 || recoveryBatchSize < 1 || recoveryBaseIntervalSeconds < 1
        || recoveryMaxBackoffSeconds < recoveryBaseIntervalSeconds) {
      throw new IllegalArgumentException("Invalid recovery limits");
    }
    this.storage = storage;
    this.persistenceError = persistenceError;
    this.journal =
        new JournalWriter(journalPrimitive, journalQueueCapacity,
            this::recordJournalFailure);
    this.pendingAdministrativeOperations =
        new PendingAdministrativeOperations(globalMutationLimit);
    this.perPlayerQueueLimit = perPlayerQueueLimit;
    this.globalMutationLimit = globalMutationLimit;
    this.recoveryBatchSize = recoveryBatchSize;
    this.recoveryBaseIntervalSeconds = recoveryBaseIntervalSeconds;
    this.recoveryMaxBackoffSeconds = recoveryMaxBackoffSeconds;
    this.recoveryBackoffSeconds.set(recoveryBaseIntervalSeconds);
    this.nextRecoveryNanos.set(
        System.nanoTime() + TimeUnit.SECONDS.toNanos(recoveryBaseIntervalSeconds));
    this.leaderboardCacheNanos = Duration.ofSeconds(leaderboardCacheSeconds).toNanos();
    this.journalDrainTimeoutSeconds = shutdownDrainTimeoutSeconds;
    this.databaseDrainTimeoutSeconds = shutdownDrainTimeoutSeconds;
    int workers = storage.workerThreads();
    this.io =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(executorQueueLimit),
            Thread.ofPlatform().name("NovaCoins-Storage-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    this.recoveryScheduler =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("NovaCoins-Recovery").factory());
    recoveryScheduler.setRemoveOnCancelPolicy(true);
    recoveryScheduler.scheduleWithFixedDelay(
        () -> {
          if (accepting && System.nanoTime() >= nextRecoveryNanos.get()) replayRecovery();
          long now = System.nanoTime();
          long reconcileAt = nextJournalReconcileNanos.get();
          if (accepting && now >= reconcileAt
              && nextJournalReconcileNanos.compareAndSet(
                  reconcileAt, now + TimeUnit.MINUTES.toNanos(10))) {
            journal.reconcile().exceptionally(error -> {
              recordJournalFailure(error);
              return null;
            });
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    journal.ready().thenCompose(ignored -> journal.loadAll()).whenComplete((operations, error) -> {
      if (error != null) {
        recordJournalFailure(error);
        administrativeRegistryReady.completeExceptionally(error);
        return;
      }
      for (EconomyOperation operation : operations) restoreAdministrativePending(operation);
      administrativeRegistryReady.complete(null);
    });
  }

  public CompletableFuture<RecoveryReport> replayRecovery() {
    if (!recoveryRunning.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(
          new RecoveryReport(0, journal.metrics().pending(), false, 0));
    }
    debugLog.accept("recovery batch start size=" + recoveryBatchSize);
    CompletableFuture<RecoveryReport> replay = journal.loadBatch(recoveryBatchSize)
        .thenCompose(operations -> submitIo(() -> processRecoveryBatch(operations)))
        .thenCompose(progress -> CompletableFuture
            .allOf(progress.removals.toArray(CompletableFuture[]::new))
            .thenApply(ignored ->
                new RecoveryReport(progress.recovered, journal.metrics().pending(),
                    progress.storageFailed, progress.actualProgress)));
    CompletableFuture<RecoveryReport> completed = replay.whenComplete((report, error) -> {
      if (error != null) {
        fastRecoveryScheduled.set(false);
        recoveryFailed.increment();
        scheduleRecoveryBackoff();
      } else {
        debugLog.accept("recovery batch end inspected=" + report.recovered()
            + " progress=" + report.actualProgress() + " pending=" + report.stillPending());
        if (progressStorageFailed(report)) {
          fastRecoveryScheduled.set(false);
          scheduleRecoveryBackoff();
        } else if (report.stillPending() == 0) {
          fastRecoveryScheduled.set(false);
          recoveryBackoffSeconds.set(recoveryBaseIntervalSeconds);
          nextRecoveryNanos.set(System.nanoTime()
              + TimeUnit.SECONDS.toNanos(recoveryBaseIntervalSeconds));
          if (health != StorageHealth.UNAVAILABLE) recordSuccess();
        } else {
          recordSuccess();
          boolean fast = report.actualProgress() >= recoveryBatchSize;
          fastRecoveryScheduled.set(fast);
          long delay = fast ? 1 : recoveryBaseIntervalSeconds;
          nextRecoveryNanos.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(delay));
        }
      }
      recoveryRunning.set(false);
    });
    return completed;
  }

  private RecoveryProgress processRecoveryBatch(List<EconomyOperation> operations) {
    int recovered = 0;
    int actualProgress = 0;
    boolean storageFailed = false;
    List<CompletableFuture<Void>> removals = new ArrayList<>();
    if (!operations.isEmpty()) health = StorageHealth.RECOVERING;
    for (EconomyOperation operation : operations) {
      restoreAdministrativePending(operation);
      try {
        DurableMutationResult result = applyRecoveryOperation(operation);
        if (!result.committed()) {
          removals.add(forgetOperation(operation.operationId(), false));
          recovered++;
          actualProgress++;
          continue;
        }
        CoinTransaction durable = storage.findTransaction(operation.operationId())
            .orElseThrow(() -> new IllegalStateException("Committed operation has no transaction"));
        TransactionStatus currentStatus = durable.status();
        boolean operationProgress = result.status() == DurableMutationResult.Status.APPLIED;
        if (operation.type() == TransactionType.PLAYTIME_REWARD) {
          recoveredRewardReady.accept(operation.accountId());
        }
        if (currentStatus == TransactionStatus.DELIVERY_STARTED) {
          if (!activeDeliveries.contains(operation.operationId())) {
            storage.markDelivery(operation.operationId(), TransactionStatus.MANUAL_REVIEW,
                "Servidor reiniciado después de iniciar una entrega; revisión manual requerida");
            manualReviewCount.incrementAndGet();
            manualReviewCreated.accept(operation.operationId());
            debugLog.accept("manual review created " + operation.operationId());
            removals.add(forgetOperation(operation.operationId()));
            operationProgress = true;
          }
        } else if (isRecoverableDelivery(currentStatus)) {
          rememberPendingDelivery(operation, currentStatus);
          deliveryReady.accept(operation.accountId());
        } else {
          removals.add(forgetOperation(operation.operationId()));
          operationProgress = true;
        }
        if (operationProgress) actualProgress++;
        recovered++;
        recoveryProcessed.increment();
      } catch (Exception storageFailure) {
        recoveryFailed.increment();
        recordFailure(storageFailure);
        storageFailed = true;
        break;
      }
    }
    return new RecoveryProgress(recovered, actualProgress, removals, storageFailed);
  }

  private boolean progressStorageFailed(RecoveryReport report) { return report.storageFailed(); }

  private void scheduleRecoveryBackoff() {
    int delay = recoveryBackoffSeconds.getAndUpdate(current ->
        Math.min(recoveryMaxBackoffSeconds, Math.max(recoveryBaseIntervalSeconds, current * 2)));
    nextRecoveryNanos.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(delay));
  }

  private DurableMutationResult applyRecoveryOperation(EconomyOperation operation)
      throws Exception {
    AccountHandle handle =
        handles.computeIfAbsent(operation.accountId(), ignored -> new AccountHandle());
    synchronized (handle.operationLock) {
      DurableMutationResult result = storage.applyOperation(operation);
      if (result.committed() && handle.account != null) {
        handle.account.applyCommitted(result.account());
      }
      if (result.committed()) {
        handle.settledSequence.accumulateAndGet(operation.accountSequence(), Math::max);
      }
      return result;
    }
  }

  public CompletableFuture<PlayerAccount> load(UUID uuid, String name) {
    if (!accepting) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("WalletService is shutting down"));
    }
    AccountHandle handle = handles.computeIfAbsent(uuid, ignored -> new AccountHandle());
    synchronized (handle) {
      handle.evictWhenIdle = false;
      if (handle.account != null) return CompletableFuture.completedFuture(handle.account);
      if (handle.loading != null) return handle.loading;
      handle.state = AccountState.LOADING;
      CompletableFuture<PlayerAccount> loading =
          submitIo(
              () -> {
                try {
                  PlayerAccount account;
                  synchronized (handle.operationLock) {
                    account = storage.loadOrCreate(uuid, name);
                    handle.sequence.accumulateAndGet(storage.maxAccountSequence(uuid), Math::max);
                    handle.sequence.accumulateAndGet(journal.maxSequence(uuid), Math::max);
                    handle.account = account;
                    handle.state = pendingAdministrativeOperations.isPending(uuid)
                        ? AccountState.ADMIN_MUTATION_PENDING : AccountState.READY;
                  }
                  recordSuccess();
                  return account;
                } catch (Exception exception) {
                  handle.state = AccountState.FAILED;
                  recordFailure(exception);
                  throw exception;
                }
              });
      handle.loading = loading;
      loading.whenComplete(
          (account, error) -> {
            synchronized (handle) {
              handle.loading = null;
            }
          });
      return loading;
    }
  }

  public Optional<PlayerAccount> account(UUID uuid) {
    AccountHandle handle = handles.get(uuid);
    return handle == null ? Optional.empty() : Optional.ofNullable(handle.account);
  }

  public AccountState accountState(UUID uuid) {
    if (pendingAdministrativeOperations.isPending(uuid)) {
      return AccountState.ADMIN_MUTATION_PENDING;
    }
    AccountHandle handle = handles.get(uuid);
    return handle == null ? AccountState.LOADING : handle.state;
  }

  public boolean isLoading(UUID uuid) {
    return accountState(uuid) == AccountState.LOADING;
  }

  public int cachedAccounts() {
    return (int) handles.values().stream().filter(handle -> handle.account != null).count();
  }

  public CompletableFuture<EconomyResult> credit(
      UUID uuid, long amount, TransactionType type, String reason, String reference) {
    return mutate(
        EconomyOperation.create(
            uuid,
            MutationKind.CREDIT,
            amount,
            type,
            reason,
            reference,
            TransactionStatus.COMMITTED));
  }

  public CompletableFuture<EconomyResult> creditForDelivery(
      UUID uuid, long amount, TransactionType type, String reason, String reference) {
    return mutate(
        EconomyOperation.create(
            uuid,
            MutationKind.CREDIT,
            amount,
            type,
            reason,
            reference,
            TransactionStatus.DELIVERY_PENDING));
  }

  public CompletableFuture<EconomyResult> debit(
      UUID uuid, long amount, TransactionType type, String reason, String reference) {
    return debitForDelivery(uuid, amount, type, reason, reference, false);
  }

  public CompletableFuture<EconomyResult> debitForDelivery(
      UUID uuid,
      long amount,
      TransactionType type,
      String reason,
      String reference,
      boolean deliveryPending) {
    return mutate(
        EconomyOperation.create(
            uuid,
            MutationKind.DEBIT,
            amount,
            type,
            reason,
            reference,
            deliveryPending ? TransactionStatus.DELIVERY_PENDING : TransactionStatus.COMMITTED));
  }

  public CompletableFuture<EconomyResult> set(
      UUID uuid, long amount, TransactionType type, String reason, String reference) {
    return mutate(
        EconomyOperation.create(
            uuid,
            MutationKind.SET,
            amount,
            type,
            reason,
            reference,
            TransactionStatus.COMMITTED));
  }

  /** A new admin operation is accepted only when it can first be captured durably. */
  public boolean canAcceptAdministrativeMutation(UUID uuid) {
    if (!administrativeRegistryReady.isDone()
        || administrativeRegistryReady.isCompletedExceptionally()
        || !accepting || health != StorageHealth.HEALTHY
        || journal.health() != JournalHealth.HEALTHY
        || pendingAdministrativeOperations.isPending(uuid)
        || journal.pendingForAccount(uuid) != 0
        || pendingMutations.get() >= globalMutationLimit
        || io.getQueue().remainingCapacity() == 0) return false;
    AccountHandle handle = handles.get(uuid);
    if (handle == null || handle.account == null || handle.state != AccountState.READY) return false;
    synchronized (handle.captureLock) {
      if (handle.capturing != 0) return false;
      synchronized (handle) {
        return !handle.processing && handle.queue.isEmpty();
      }
    }
  }

  public CompletableFuture<EconomyResult> administrativeMutation(
      UUID uuid, MutationKind kind, long amount, TransactionType type, String reason,
      String reference) {
    if (!administrativeRegistryReady.isDone()) {
      return administrativeRegistryReady.handle((ignored, error) -> error)
          .thenCompose(error -> error == null
              ? administrativeMutation(uuid, kind, amount, type, reason, reference)
              : CompletableFuture.completedFuture(EconomyResult.failed(
                  EconomyResult.Status.STORAGE_UNAVAILABLE, UUID.randomUUID())));
    }
    AccountHandle handle = handles.get(uuid);
    Optional<PendingAdministrativeOperations.PendingOperation> existing =
        pendingAdministrativeOperations.pending(uuid);
    if (existing.isPresent()) {
      PendingAdministrativeOperations.PendingOperation pending = existing.get();
      long balance = handle == null || handle.account == null ? 0 : handle.account.balance();
      return CompletableFuture.completedFuture(new EconomyResult(
          EconomyResult.Status.ADMIN_ALREADY_PENDING, pending.operationId(), balance, balance,
          true, true));
    }
    UUID operationId = UUID.randomUUID();
    if (!canAcceptAdministrativeMutation(uuid) || handle == null) {
      return CompletableFuture.completedFuture(
          EconomyResult.failed(EconomyResult.Status.STORAGE_UNAVAILABLE, operationId));
    }
    EconomyOperation operation;
    synchronized (handle.captureLock) {
      if (!canAcceptAdministrativeMutation(uuid)) {
        return CompletableFuture.completedFuture(
            EconomyResult.failed(EconomyResult.Status.STORAGE_UNAVAILABLE, operationId));
      }
      long seed = Math.multiplyExact(System.currentTimeMillis(), 1_000L);
      long sequence = handle.sequence.updateAndGet(previous -> Math.max(seed, previous + 1));
      operation = new EconomyOperation(operationId, uuid, kind, amount, type, reason, reference,
          TransactionStatus.COMMITTED, java.time.Instant.now(), sequence);
      if (pendingAdministrativeOperations.register(operation)
          != PendingAdministrativeOperations.Registration.ADDED) {
        return CompletableFuture.completedFuture(
            EconomyResult.failed(EconomyResult.Status.STORAGE_UNAVAILABLE, operationId));
      }
      handle.state = AccountState.ADMIN_MUTATION_PENDING;
    }
    OperationSubmission submission = capture(operation);
    if (!submission.captured()) {
      resolveAdministrative(operationId, false);
      return submission.result();
    }
    submission.durability().whenComplete((ignored, journalError) -> {
      if (journalError != null) resolveAdministrative(operationId, false);
    });
    return submission.result().thenApply(result -> {
      if (result.status() == EconomyResult.Status.RECOVERY_PENDING) {
        logAdministrativePending(operationId);
        return new EconomyResult(EconomyResult.Status.ADMIN_PENDING, operationId,
            result.balanceBefore(), result.balanceAfter(), true, true);
      }
      return result;
    });
  }

  public CompletableFuture<EconomyResult> refundPurchase(
      UUID uuid, long amount, String reason, UUID purchaseOperationId) {
    UUID refundId =
        UUID.nameUUIDFromBytes(
            ("novacoins:refund:" + purchaseOperationId).getBytes(StandardCharsets.UTF_8));
    return mutate(
        new EconomyOperation(
            refundId,
            uuid,
            MutationKind.REFUND_DEBIT,
            amount,
            TransactionType.REFUND,
            reason,
            purchaseOperationId.toString(),
            TransactionStatus.REFUNDED,
            java.time.Instant.now()));
  }

  public CompletableFuture<EconomyResult> mutate(EconomyOperation operation) {
    return capture(operation).result();
  }

  /**
   * Enqueues capture without blocking the caller. SQL is offered only after the journal future
   * confirms fsync and rename. Until then the submission is CAPTURING, not CAPTURED.
   */
  public OperationSubmission capture(EconomyOperation operation) {
    return capture(operation, false);
  }

  /** Capture whose owner is guaranteed to retry the same immutable operation id. */
  public OperationSubmission captureRetriable(EconomyOperation operation) {
    return capture(operation, true);
  }

  private OperationSubmission capture(EconomyOperation operation, boolean retainForSameIdRetry) {
    UUID operationId = operation.operationId();
    if (!accepting) {
      return OperationSubmission.rejected(
          EconomyResult.failed(EconomyResult.Status.SHUTTING_DOWN, operationId));
    }
    if (operation.amount() < 0
        || (operation.kind() != MutationKind.SET && operation.amount() == 0)) {
      return OperationSubmission.rejected(
          EconomyResult.failed(EconomyResult.Status.INVALID_AMOUNT, operationId));
    }
    AccountHandle sequenceHandle =
        handles.computeIfAbsent(operation.accountId(), ignored -> new AccountHandle());
    CompletableFuture<EconomyResult> result = new CompletableFuture<>();
    JournalWriter.StoreSubmission queued;
    EconomyOperation sequenced;
    synchronized (sequenceHandle.captureLock) {
      long sequence = assignedSequences.computeIfAbsent(operationId, ignored -> {
        if (operation.accountSequence() > 0) {
          sequenceHandle.sequence.accumulateAndGet(operation.accountSequence(), Math::max);
          return operation.accountSequence();
        }
        long seed = Math.multiplyExact(System.currentTimeMillis(), 1_000L);
        long durableFloor = journal.maxSequence(operation.accountId());
        sequenceHandle.sequence.accumulateAndGet(durableFloor, Math::max);
        return sequenceHandle.sequence.updateAndGet(previous -> Math.max(seed, previous + 1));
      });
      sequenced = operation.withAccountSequence(sequence);
      queued = journal.store(sequenced);
      if (queued.accepted()) sequenceHandle.capturing++;
    }
    if (!queued.accepted()) {
      if (!retainForSameIdRetry) assignedSequences.remove(operationId, sequenced.accountSequence());
      rejected.increment();
      result.complete(EconomyResult.failed(EconomyResult.Status.JOURNAL_UNAVAILABLE, operationId));
      return OperationSubmission.rejected(result);
    }
    EconomyOperation capturedOperation = sequenced;
    queued.durable().whenComplete((ignored, journalError) -> {
      if (journalError != null) {
        synchronized (sequenceHandle.captureLock) { sequenceHandle.capturing--; }
        if (!retainForSameIdRetry) {
          assignedSequences.remove(operationId, capturedOperation.accountSequence());
        }
        failed.increment();
        result.complete(
            EconomyResult.failed(EconomyResult.Status.JOURNAL_UNAVAILABLE, operationId));
        return;
      }
      debugLog.accept("operation captured " + operationId + " " + capturedOperation.type());
      if (capturedOperation.initialStatus() == TransactionStatus.DELIVERY_PENDING) {
        rememberPendingPurchase(capturedOperation);
      }
      afterDurableCapture(capturedOperation, result);
      synchronized (sequenceHandle.captureLock) { sequenceHandle.capturing--; }
    });
    return OperationSubmission.capturing(queued.durable(), result);
  }

  private void afterDurableCapture(
      EconomyOperation operation, CompletableFuture<EconomyResult> future) {
    if (health != StorageHealth.HEALTHY) {
      future.complete(recoveryPending(operation));
      return;
    }
    AccountHandle handle = handles.get(operation.accountId());
    if (handle == null || handle.account == null || handle.state == AccountState.LOADING) {
      future.complete(recoveryPending(operation));
      return;
    }
    if (handle.state == AccountState.READ_ONLY || handle.state == AccountState.FAILED) {
      future.complete(recoveryPending(operation));
      return;
    }
    Optional<UUID> pendingAdmin = pendingAdministrativeOperations.operationId(operation.accountId());
    if (pendingAdmin.isPresent() && !pendingAdmin.get().equals(operation.operationId())) {
      future.complete(recoveryPending(operation));
      return;
    }
    synchronized (handle) {
      if (journal.hasUnsettledEarlier(operation.accountId(), operation.accountSequence(),
              handle.settledSequence.get())
          && !handle.processing && handle.queue.isEmpty()
          ) {
        future.complete(recoveryPending(operation));
        return;
      }
      if (handle.queue.size() >= perPlayerQueueLimit
          || pendingMutations.incrementAndGet() > globalMutationLimit) {
        pendingMutations.decrementAndGet();
        rejected.increment();
        future.complete(recoveryPending(operation));
        return;
      }
      submitted.increment();
      handle.queue.addLast(new MutationRequest(operation, future));
      handle.state = AccountState.MUTATING;
      if (!handle.processing) {
        handle.processing = true;
        scheduleNext(handle);
      }
    }
  }

  private EconomyResult recoveryPending(EconomyOperation operation) {
    AccountHandle handle = handles.get(operation.accountId());
    long balance = handle == null || handle.account == null ? 0 : handle.account.balance();
    return new EconomyResult(
        EconomyResult.Status.RECOVERY_PENDING,
        operation.operationId(),
        balance,
        balance,
        false,
        true);
  }

  private void scheduleNext(AccountHandle handle) {
    try {
      io.execute(() -> processNext(handle));
    } catch (RejectedExecutionException rejectedExecution) {
      List<MutationRequest> abandoned = new ArrayList<>();
      synchronized (handle) {
        MutationRequest request;
        while ((request = handle.queue.pollFirst()) != null) abandoned.add(request);
        handle.processing = false;
        handle.state = handle.account == null
            ? AccountState.FAILED : idleState(handle.account.uuid(), handle);
      }
      for (MutationRequest request : abandoned) {
        pendingMutations.decrementAndGet();
        rejected.increment();
        request.future.complete(recoveryPending(request.operation));
      }
      signalDrain();
    }
  }

  private void processNext(AccountHandle handle) {
    MutationRequest request;
    synchronized (handle) {
      request = handle.queue.peekFirst();
    }
    if (request == null) {
      synchronized (handle) {
        handle.processing = false;
        handle.state = handle.account == null
            ? AccountState.FAILED : idleState(handle.account.uuid(), handle);
      }
      return;
    }

    EconomyResult result;
    try {
      synchronized (handle.operationLock) {
        DurableMutationResult durable = storage.applyOperation(request.operation);
        result = mapResult(request.operation, durable);
        if (durable.committed()) {
          handle.account.applyCommitted(durable.account());
          if (durable.status() == DurableMutationResult.Status.DUPLICATE) duplicates.increment();
          else committed.increment();
          TransactionStatus currentStatus =
              java.util.Objects.requireNonNull(
                      durable.transaction(), "Committed mutation has no transaction")
                  .status();
          if (isRecoverableDelivery(currentStatus)) {
            rememberPendingDelivery(request.operation, currentStatus);
          } else {
            forgetOperation(request.operation.operationId());
          }
          recordSuccess();
        } else {
          forgetOperation(request.operation.operationId(), false);
        }
        handle.settledSequence.accumulateAndGet(
            request.operation.accountSequence(), Math::max);
      }
    } catch (Exception exception) {
      failed.increment();
      recordFailure(exception);
      result = recoveryPending(request.operation);
    }
    request.future.complete(result);
    synchronized (handle) {
      handle.queue.pollFirst();
      pendingMutations.decrementAndGet();
      signalDrain();
      if (health == StorageHealth.UNAVAILABLE
          && !pendingAdministrativeOperations.isPending(request.operation.accountId())) {
        handle.state = AccountState.READ_ONLY;
      } else {
        handle.state = handle.queue.isEmpty()
            ? idleState(request.operation.accountId(), handle) : AccountState.MUTATING;
      }
      if (handle.queue.isEmpty()) {
        handle.processing = false;
        if (handle.evictWhenIdle && handle.account != null
            && !pendingAdministrativeOperations.isPending(handle.account.uuid())) {
          handles.remove(handle.account.uuid(), handle);
        }
      } else {
        scheduleNext(handle);
      }
    }
  }

  private EconomyResult mapResult(EconomyOperation operation, DurableMutationResult result) {
    EconomyResult.Status status =
        switch (result.status()) {
          case APPLIED -> EconomyResult.Status.SUCCESS;
          case DUPLICATE -> EconomyResult.Status.DUPLICATE;
          case INSUFFICIENT_FUNDS -> EconomyResult.Status.INSUFFICIENT_FUNDS;
          case INVALID_AMOUNT -> EconomyResult.Status.INVALID_AMOUNT;
          case OVERFLOW -> EconomyResult.Status.OVERFLOW;
          case ACCOUNT_MISSING -> EconomyResult.Status.ACCOUNT_NOT_READY;
        };
    long before =
        result.transaction() == null && result.account() != null
            ? result.account().balance()
            : result.transaction() == null ? 0 : result.transaction().balanceBefore();
    long after =
        result.transaction() == null && result.account() != null
            ? result.account().balance()
            : result.transaction() == null ? 0 : result.transaction().balanceAfter();
    return new EconomyResult(
        status, operation.operationId(), before, after, result.committed(), false);
  }

  public CompletableFuture<Void> markDelivery(
      UUID operationId, TransactionStatus status, String error) {
    return submitIo(
        () -> {
          boolean starting = status == TransactionStatus.DELIVERY_STARTED;
          TransactionStatus previousStatus = deliveryStatuses.get(operationId);
          if (starting) activeDeliveries.add(operationId);
          try {
          storage.markDelivery(operationId, status, error);
          } catch (Exception exception) {
            if (starting) activeDeliveries.remove(operationId);
            throw exception;
          }
          deliveryStatuses.put(operationId, status);
          if (status == TransactionStatus.MANUAL_REVIEW
              && previousStatus != TransactionStatus.MANUAL_REVIEW) {
            manualReviewCount.incrementAndGet();
            manualReviewCreated.accept(operationId);
          }
          if (status.terminal()) {
            activeDeliveries.remove(operationId);
            forgetOperation(operationId);
          }
          return null;
        });
  }

  private boolean isRecoverableDelivery(TransactionStatus status) {
    return status == TransactionStatus.DELIVERY_PENDING || status.needsSafeRefund();
  }

  private void rememberPendingDelivery(EconomyOperation operation, TransactionStatus status) {
    rememberPendingPurchase(operation);
    pendingDeliveries.put(operation.operationId(), operation);
    deliveryStatuses.put(operation.operationId(), status);
  }

  private void rememberPendingPurchase(EconomyOperation operation) {
    if (pendingPurchases.putIfAbsent(operation.operationId(), operation) == null) {
      pendingPurchaseCounts.compute(
          operation.accountId(),
          (ignored, count) -> {
            if (count == null) return new AtomicInteger(1);
            count.incrementAndGet();
            return count;
          });
    }
  }

  private CompletableFuture<Void> forgetOperation(UUID operationId) {
    return forgetOperation(operationId, true);
  }

  private CompletableFuture<Void> forgetOperation(UUID operationId, boolean committedResult) {
    EconomyOperation pending = pendingPurchases.remove(operationId);
    if (pending != null) {
      pendingPurchaseCounts.computeIfPresent(
          pending.accountId(), (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }
    pendingDeliveries.remove(operationId);
    deliveryStatuses.remove(operationId);
    activeDeliveries.remove(operationId);
    assignedSequences.remove(operationId);
    resolveAdministrative(operationId, committedResult);
    CompletableFuture<Void> removal = journal.remove(operationId);
    removal.whenComplete((ignored, error) -> {
      if (error != null) recordJournalFailure(error);
    });
    return removal;
  }

  public List<EconomyOperation> pendingDeliveries(UUID accountId) {
    List<EconomyOperation> output = new ArrayList<>();
    for (EconomyOperation operation : pendingDeliveries.values()) {
      if (operation.accountId().equals(accountId)) output.add(operation);
    }
    return List.copyOf(output);
  }

  public Optional<EconomyOperation> pendingDelivery(UUID operationId) {
    return Optional.ofNullable(pendingDeliveries.get(operationId));
  }

  public Optional<TransactionStatus> pendingDeliveryStatus(UUID operationId) {
    return Optional.ofNullable(deliveryStatuses.get(operationId));
  }

  public boolean hasPendingPurchase(UUID accountId) {
    AtomicInteger count = pendingPurchaseCounts.get(accountId);
    return count != null && count.get() > 0;
  }

  public void onDeliveryReady(Consumer<UUID> listener) {
    deliveryReady = listener == null ? ignored -> {} : listener;
  }

  public void onRecoveredRewardReady(Consumer<UUID> listener) {
    recoveredRewardReady = listener == null ? ignored -> {} : listener;
  }

  public void onManualReviewCreated(Consumer<UUID> listener) {
    manualReviewCreated = listener == null ? ignored -> {} : listener;
  }

  public CompletableFuture<Optional<StorageProvider.RewardNotification>> claimRewardNotification(
      UUID accountId) {
    return submitIo(() -> storage.claimRewardNotification(accountId));
  }

  public int assignedSequenceCount() { return assignedSequences.size(); }

  public boolean fastRecoveryScheduled() { return fastRecoveryScheduled.get(); }

  public void onDebug(Consumer<String> logger) {
    debugLog = logger == null ? ignored -> {} : logger;
    journal.onDebug(debugLog);
  }

  public void onAdministrativeLog(Consumer<String> logger) {
    administrativeLog = logger == null ? ignored -> {} : logger;
  }

  public Optional<PendingAdministrativeOperations.PendingOperation> pendingAdministrativeOperation(
      UUID accountId) {
    return pendingAdministrativeOperations.pending(accountId);
  }

  public Optional<PendingAdministrativeOperations.PendingOperation> pendingAdministrativeOperation(
      String lastName) {
    for (AccountHandle handle : handles.values()) {
      PlayerAccount account = handle.account;
      if (account != null && account.lastName().equalsIgnoreCase(lastName)) {
        Optional<PendingAdministrativeOperations.PendingOperation> pending =
            pendingAdministrativeOperations.pending(account.uuid());
        if (pending.isPresent()) return pending;
      }
    }
    return Optional.empty();
  }

  public int pendingAdministrativeOperations() { return pendingAdministrativeOperations.size(); }

  public void configureShutdownTimeout(int seconds) {
    if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("Invalid shutdown timeout");
    journalDrainTimeoutSeconds = seconds;
    databaseDrainTimeoutSeconds = seconds;
  }

  public void configureShutdownTimeouts(int journalSeconds, int databaseSeconds) {
    if (journalSeconds < 1 || journalSeconds > 30 || databaseSeconds < 1 || databaseSeconds > 30) {
      throw new IllegalArgumentException("Invalid shutdown timeout");
    }
    journalDrainTimeoutSeconds = journalSeconds;
    databaseDrainTimeoutSeconds = databaseSeconds;
  }

  public CompletableFuture<Void> unload(UUID uuid) {
    AccountHandle handle = handles.get(uuid);
    if (handle == null) return CompletableFuture.completedFuture(null);
    synchronized (handle) {
      if (handle.processing || !handle.queue.isEmpty() || handle.capturing != 0
          || pendingAdministrativeOperations.isPending(uuid)) {
        handle.evictWhenIdle = true;
        return CompletableFuture.completedFuture(null);
      }
      handles.remove(uuid, handle);
    }
    return CompletableFuture.completedFuture(null);
  }

  public CompletableFuture<List<StorageProvider.LeaderboardEntry>> leaderboard(
      int page, int pageSize) {
    int offset = safeOffset(page, pageSize);
    LeaderboardKey key = new LeaderboardKey(page, pageSize);
    CacheEntry cached = leaderboardCache.get(key);
    long now = System.nanoTime();
    if (cached != null && now - cached.loadedAtNanos < leaderboardCacheNanos) {
      return CompletableFuture.completedFuture(cached.entries);
    }
    return submitIo(
        () -> {
          cleanupLeaderboardCache(System.nanoTime());
          List<StorageProvider.LeaderboardEntry> entries =
              List.copyOf(storage.leaderboard(offset, pageSize));
          leaderboardCache.put(key, new CacheEntry(entries, System.nanoTime()));
          trimLeaderboardCache();
          return entries;
        });
  }

  public CompletableFuture<List<CoinTransaction>> history(UUID uuid, int page, int pageSize) {
    int offset = safeOffset(page, pageSize);
    return submitIo(() -> storage.history(uuid, offset, pageSize));
  }

  public CompletableFuture<List<CoinTransaction>> deliveryFailures(int page, int pageSize) {
    int offset = safeOffset(page, pageSize);
    return submitIo(() -> storage.deliveryFailures(offset, pageSize));
  }

  public CompletableFuture<Optional<CoinTransaction>> transaction(UUID operationId) {
    return submitIo(() -> storage.findTransaction(operationId));
  }

  public CompletableFuture<Void> resolveReviewDelivered(UUID operationId, String adminId) {
    if (health != StorageHealth.HEALTHY) {
      return CompletableFuture.failedFuture(new IllegalStateException("Storage is not healthy"));
    }
    return submitIo(() -> {
      storage.resolveManualReview(operationId, TransactionStatus.DELIVERED, adminId,
          "REVIEW_DELIVERED", "Staff verificó externamente la entrega");
      manualReviewCount.updateAndGet(value -> Math.max(0, value - 1));
      debugLog.accept("manual review delivered " + operationId + " by " + adminId);
      return null;
    });
  }

  public CompletableFuture<EconomyResult> resolveReviewRefund(UUID operationId, String adminId) {
    if (health != StorageHealth.HEALTHY || journal.health() != JournalHealth.HEALTHY) {
      return CompletableFuture.failedFuture(new IllegalStateException("Storage is not healthy"));
    }
    return transaction(operationId).thenCompose(found -> {
      CoinTransaction purchase = found.orElseThrow(
          () -> new IllegalArgumentException("Unknown operation"));
      AccountHandle handle = handles.computeIfAbsent(purchase.uuid(), ignored -> new AccountHandle());
      return submitIo(() -> {
        synchronized (handle.operationLock) {
          DurableMutationResult durable = storage.resolveManualReviewRefund(operationId, adminId);
          CoinTransaction refund = java.util.Objects.requireNonNull(durable.transaction());
          if (handle.account != null) handle.account.applyCommitted(durable.account());
          handle.settledSequence.accumulateAndGet(refund.accountSequence(), Math::max);
          debugLog.accept("manual review refund " + operationId + " by " + adminId);
          if (durable.status() != DurableMutationResult.Status.DUPLICATE) {
            manualReviewCount.updateAndGet(value -> Math.max(0, value - 1));
          }
          return mapResult(new EconomyOperation(
              refund.operationId(), refund.uuid(), MutationKind.REFUND_DEBIT, refund.amount(),
              refund.type(), refund.reason(), refund.reference(), refund.status(), refund.createdAt(),
              refund.accountSequence()), durable);
        }
      });
    });
  }

  public CompletableFuture<EconomyResult> resolveReviewRetry(UUID operationId, String adminId) {
    if (health != StorageHealth.HEALTHY || journal.health() != JournalHealth.HEALTHY) {
      return CompletableFuture.failedFuture(new IllegalStateException("Storage is not healthy"));
    }
    return transaction(operationId).thenCompose(found -> {
      CoinTransaction transaction = found.orElseThrow(
          () -> new IllegalArgumentException("Unknown operation"));
      if (transaction.status() != TransactionStatus.MANUAL_REVIEW
          || transaction.type() != TransactionType.SHOP_PURCHASE) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("Operation cannot be retried"));
      }
      AccountHandle handle = handles.computeIfAbsent(transaction.uuid(), ignored -> new AccountHandle());
      long sequence = transaction.accountSequence();
      if (sequence <= 0) {
        long seed = Math.multiplyExact(System.currentTimeMillis(), 1_000L);
        sequence = handle.sequence.updateAndGet(previous -> Math.max(seed, previous + 1));
      }
      EconomyOperation retryOperation = new EconomyOperation(
          transaction.operationId(), transaction.uuid(), MutationKind.DEBIT,
          transaction.amount(), transaction.type(), transaction.reason(), transaction.reference(),
          TransactionStatus.DELIVERY_PENDING, transaction.createdAt(), sequence);
      JournalWriter.StoreSubmission captured = journal.store(retryOperation);
      if (!captured.accepted()) return CompletableFuture.failedFuture(
          new IllegalStateException("Journal queue is unavailable"));
      return captured.durable().thenCompose(ignored -> submitIo(() -> {
        storage.resolveManualReview(operationId, TransactionStatus.DELIVERY_PENDING, adminId,
            "REVIEW_RETRY", "Retry peligroso confirmado por staff");
        manualReviewCount.updateAndGet(value -> Math.max(0, value - 1));
        rememberPendingDelivery(retryOperation, TransactionStatus.DELIVERY_PENDING);
        long balance = handle.account == null ? transaction.balanceAfter() : handle.account.balance();
        debugLog.accept("manual review retry " + operationId + " by " + adminId);
        return new EconomyResult(EconomyResult.Status.SUCCESS, operationId, balance, balance,
            true, false);
      }));
    });
  }

  public CompletableFuture<OperationalStatus> operationalStatus() {
    return submitIo(
        () -> {
          try {
            long reviews = storage.countTransactions(TransactionStatus.MANUAL_REVIEW);
            manualReviewCount.set(reviews);
            OperationalStatus status = new OperationalStatus(
                journal.metrics().pending(), pendingPurchases.size(), reviews);
            recordSuccess();
            return status;
          } catch (Exception failure) {
            recordFailure(failure);
            throw failure;
          }
        });
  }

  public CompletableFuture<List<CorruptRecoveryRecord>> corruptRecoveryRecords() {
    return journal.corruptRecords();
  }

  public JournalHealth journalHealth() { return journal.health(); }

  public boolean canAcceptPurchase(UUID accountId) {
    return accepting && health == StorageHealth.HEALTHY
        && !pendingAdministrativeOperations.isPending(accountId)
        && journal.health() == JournalHealth.HEALTHY
        && journal.pendingForAccount(accountId) == 0
        && journal.queueSize() < journal.queueCapacity();
  }

  public int leaderboardCacheSize() {
    return leaderboardCache.size();
  }

  public static int safeOffset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("Invalid page or page size");
    }
    return Math.toIntExact(Math.multiplyExact((long) page - 1L, pageSize));
  }

  private void cleanupLeaderboardCache(long now) {
    leaderboardCache.entrySet().removeIf(e -> now - e.getValue().loadedAtNanos >= leaderboardCacheNanos);
  }

  private void trimLeaderboardCache() {
    while (leaderboardCache.size() > MAX_LEADERBOARD_CACHE_ENTRIES) {
      LeaderboardKey oldest = null;
      long oldestTime = Long.MAX_VALUE;
      for (var entry : leaderboardCache.entrySet()) {
        if (entry.getValue().loadedAtNanos < oldestTime) {
          oldest = entry.getKey();
          oldestTime = entry.getValue().loadedAtNanos;
        }
      }
      if (oldest == null) break;
      leaderboardCache.remove(oldest);
    }
  }

  public CompletableFuture<Optional<UUID>> findUuid(String name) {
    return submitIo(() -> storage.findUuidByName(name));
  }

  public StorageHealth health() {
    return health;
  }

  public Metrics metrics() {
    JournalWriter.Metrics journalMetrics = journal.metrics();
    return new Metrics(
        submitted.sum(),
        committed.sum(),
        duplicates.sum(),
        rejected.sum(),
        failed.sum(),
        pendingMutations.get(),
        io.getQueue().size(),
        pendingDeliveries.size(),
        journalMetrics.queueSize(),
        journalMetrics.queueCapacity(),
        journalMetrics.writing(),
        journalMetrics.writeFailures(),
        journalMetrics.averageWriteLatencyMicros(),
        journalMetrics.pending(),
        journalMetrics.corrupt(),
        recoveryProcessed.sum(),
        recoveryFailed.sum(),
        manualReviewCount.get(),
        pendingAdministrativeOperations.size());
  }

  private <T> CompletableFuture<T> submitIo(ThrowingSupplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      io.execute(
          () -> {
            try {
              future.complete(supplier.get());
            } catch (Throwable throwable) {
              future.completeExceptionally(throwable);
            }
          });
    } catch (RejectedExecutionException rejectedExecution) {
      future.completeExceptionally(rejectedExecution);
    }
    return future;
  }

  private void recordSuccess() {
    int previousFailures = consecutiveFailures.getAndSet(0);
    if (previousFailures > 0) {
      debugLog.accept("storage health transition -> RECOVERING");
      health = StorageHealth.RECOVERING;
      for (AccountHandle handle : handles.values()) {
        synchronized (handle) {
          if (handle.state == AccountState.READ_ONLY && !handle.processing) {
            handle.state = AccountState.READY;
          }
        }
      }
    }
    health = StorageHealth.HEALTHY;
    if (previousFailures > 0) {
      debugLog.accept("storage health transition -> HEALTHY");
      nextRecoveryNanos.set(System.nanoTime());
      if (!recoveryRunning.get()) {
        try {
          recoveryScheduler.execute(this::replayRecovery);
        } catch (RejectedExecutionException ignored) {
          // Shutdown owns the scheduler at this point.
        }
      }
    }
  }

  private void recordFailure(Throwable throwable) {
    int failures = consecutiveFailures.incrementAndGet();
    health = failures >= 3 ? StorageHealth.UNAVAILABLE : StorageHealth.DEGRADED;
    if (failures == 1 || failures == 3) {
      debugLog.accept("storage health transition -> " + health);
    }
    long now = System.nanoTime();
    long last = lastErrorLog.get();
    if ((last == Long.MIN_VALUE || now - last >= ERROR_LOG_INTERVAL_NANOS)
        && lastErrorLog.compareAndSet(last, now)) {
      persistenceError.accept(throwable);
    }
  }

  private void recordJournalFailure(Throwable throwable) {
    long now = System.nanoTime();
    long last = lastErrorLog.get();
    if ((last == Long.MIN_VALUE || now - last >= ERROR_LOG_INTERVAL_NANOS)
        && lastErrorLog.compareAndSet(last, now)) {
      persistenceError.accept(throwable);
    }
  }

  private void restoreAdministrativePending(EconomyOperation operation) {
    if (!PendingAdministrativeOperations.isAdministrative(operation.type())) return;
    PendingAdministrativeOperations.Registration registration =
        pendingAdministrativeOperations.register(operation);
    if (registration == PendingAdministrativeOperations.Registration.ADDED) {
      AccountHandle handle = handles.get(operation.accountId());
      if (handle != null && handle.account != null) {
        synchronized (handle) { handle.state = AccountState.ADMIN_MUTATION_PENDING; }
      }
    }
  }

  private void logAdministrativePending(UUID operationId) {
    long now = System.nanoTime();
    long last = lastAdministrativePendingLog.get();
    if ((last == Long.MIN_VALUE || now - last >= ERROR_LOG_INTERVAL_NANOS)
        && lastAdministrativePendingLog.compareAndSet(last, now)) {
      administrativeLog.accept("Administrative operation " + operationId
          + " awaiting recovery");
    }
  }

  private void resolveAdministrative(UUID operationId, boolean confirmed) {
    pendingAdministrativeOperations.resolve(operationId).ifPresent(operation -> {
      AccountHandle handle = handles.get(operation.accountId());
      if (handle != null && handle.account != null) {
        synchronized (handle) {
          if (!handle.processing && handle.queue.isEmpty()) {
            handle.state = health == StorageHealth.UNAVAILABLE
                ? AccountState.READ_ONLY : AccountState.READY;
            if (handle.evictWhenIdle) handles.remove(operation.accountId(), handle);
          }
        }
      }
      if (confirmed) administrativeLog.accept("Administrative operation "
          + operationId + " resolved: COMMITTED");
    });
  }

  private AccountState idleState(UUID accountId, AccountHandle handle) {
    if (pendingAdministrativeOperations.isPending(accountId)) {
      return AccountState.ADMIN_MUTATION_PENDING;
    }
    if (health == StorageHealth.UNAVAILABLE) return AccountState.READ_ONLY;
    return handle.account == null ? AccountState.FAILED : AccountState.READY;
  }

  @Override
  public void close() {
    int totalSeconds = Math.min(10, journalDrainTimeoutSeconds + databaseDrainTimeoutSeconds);
    closeUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(totalSeconds));
  }

  /** Keeps the journal writable until every DB worker has finished its final store/remove. */
  public ShutdownReport closeUntil(long deadlineNanos) {
    accepting = false;
    recoveryScheduler.shutdownNow();
    boolean initialJournalDrained = journal.awaitIdle(deadlineNanos);
    io.shutdown();
    try {
      long remaining = Math.max(0, deadlineNanos - System.nanoTime());
      if (!io.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
        io.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      io.shutdownNow();
    }
    long journalRemaining = Math.max(0, deadlineNanos - System.nanoTime());
    boolean finalJournalDrained = journal.drainAndClose(journalRemaining, TimeUnit.NANOSECONDS);
    int journalWritesPending = journal.queueSize() + journal.writing();
    int databaseMutationsPending = pendingMutations.get() + io.getQueue().size();
    boolean clean = initialJournalDrained && finalJournalDrained
        && journalWritesPending == 0 && databaseMutationsPending == 0;
    if (!clean) {
      persistenceError.accept(new IllegalStateException(
          journalWritesPending + " journal writes pending, " + databaseMutationsPending
              + " DB mutations pending"));
    }
    return new ShutdownReport(journalWritesPending, databaseMutationsPending, clean);
  }

  private void signalDrain() {
    if (pendingMutations.get() == 0) {
      synchronized (drainMonitor) {
        drainMonitor.notifyAll();
      }
    }
  }

  public record Metrics(
      long submitted,
      long committed,
      long duplicates,
      long rejected,
      long failed,
      int pendingMutations,
      int executorQueueSize,
      int pendingDeliveries,
      int journalQueueSize,
      int journalQueueCapacity,
      int journalWriting,
      long journalWriteFailures,
      long journalAverageWriteLatencyMicros,
      int recoveryPending,
      int recoveryCorrupt,
      long recoveryProcessed,
      long recoveryFailed,
      long manualReviewCount,
      int pendingAdministrativeOperations) {}

  public record RecoveryReport(
      int recovered, int stillPending, boolean storageFailed, int actualProgress) {
    public RecoveryReport(int recovered, int stillPending) {
      this(recovered, stillPending, false, recovered);
    }
    public RecoveryReport(int recovered, int stillPending, boolean storageFailed) {
      this(recovered, stillPending, storageFailed, recovered);
    }
  }

  public record OperationalStatus(
      int recoveryOperations, int pendingPurchases, long manualReviews) {}

  public record ShutdownReport(
      int journalWritesPending, int databaseMutationsPending, boolean clean) {}

  public record OperationSubmission(
      boolean captured, CompletableFuture<Void> durability, CompletableFuture<EconomyResult> result) {
    private static OperationSubmission rejected(EconomyResult result) {
      return rejected(CompletableFuture.completedFuture(result));
    }

    private static OperationSubmission rejected(CompletableFuture<EconomyResult> result) {
      CompletableFuture<Void> durability = CompletableFuture.failedFuture(
          new IllegalStateException("Operation was not accepted by the journal"));
      return new OperationSubmission(false, durability, result);
    }

    private static OperationSubmission capturing(
        CompletableFuture<Void> durability, CompletableFuture<EconomyResult> result) {
      return new OperationSubmission(true, durability, result);
    }
  }

  private static final class AccountHandle {
    private final ArrayDeque<MutationRequest> queue = new ArrayDeque<>();
    private final Object operationLock = new Object();
    private final Object captureLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong settledSequence = new AtomicLong();
    private volatile PlayerAccount account;
    private volatile AccountState state = AccountState.LOADING;
    private CompletableFuture<PlayerAccount> loading;
    private boolean processing;
    private boolean evictWhenIdle;
    private volatile int capturing;
  }

  private record RecoveryProgress(
      int recovered, int actualProgress, List<CompletableFuture<Void>> removals,
      boolean storageFailed) {}

  private record MutationRequest(
      EconomyOperation operation, CompletableFuture<EconomyResult> future) {}

  private record LeaderboardKey(int page, int pageSize) {}

  private record CacheEntry(
      List<StorageProvider.LeaderboardEntry> entries, long loadedAtNanos) {}

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }
}
