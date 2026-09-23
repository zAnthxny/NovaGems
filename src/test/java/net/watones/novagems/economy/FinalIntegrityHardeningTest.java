package net.watones.novagems.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.watones.novagems.session.CompletedReward;
import net.watones.novagems.session.PendingCompletedRewards;
import net.watones.novagems.session.PlayerSession;
import net.watones.novagems.session.SessionRegistry;
import net.watones.novagems.storage.DurableMutationResult;
import net.watones.novagems.storage.JournalWriter;
import net.watones.novagems.storage.RecoveryJournal;
import net.watones.novagems.storage.SQLiteStorageProvider;
import net.watones.novagems.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinalIntegrityHardeningTest {
  @TempDir Path temp;

  @Test
  void hundredAdminCommitThenThrowOperationsApplyExactlyOnce() throws Exception {
    try (SQLiteStorageProvider database = database("admin-load.db")) {
      CountDownLatch release = new CountDownLatch(1);
      CommitFirstAttemptThenThrowStorage ambiguous =
          new CommitFirstAttemptThenThrowStorage(database, release);
      WalletService wallet = wallet(ambiguous, temp.resolve("admin-load-recovery"));
      List<UUID> players = new ArrayList<>();
      List<CompletableFuture<PlayerAccount>> loads = new ArrayList<>();
      for (int index = 0; index < 100; index++) {
        UUID player = UUID.randomUUID();
        players.add(player);
        loads.add(wallet.load(player, "A" + index));
      }
      CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
      List<CompletableFuture<EconomyResult>> mutations = new ArrayList<>();
      for (UUID player : players) {
        mutations.add(wallet.administrativeMutation(player, MutationKind.CREDIT, 100,
            TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE"));
      }
      long captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (wallet.metrics().pendingMutations() < 100 && System.nanoTime() < captureDeadline) {
        Thread.sleep(5);
      }
      assertThat(wallet.metrics().pendingMutations()).isEqualTo(100);
      release.countDown();
      CompletableFuture.allOf(mutations.toArray(CompletableFuture[]::new)).join();
      assertThat(mutations).allMatch(future ->
          future.join().status() == EconomyResult.Status.ADMIN_PENDING);
      assertThat(wallet.pendingAdministrativeOperations()).isEqualTo(100);
      assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(100);
      assertThat(players).allMatch(player -> {
        try {
          return database.loadAccount(player).orElseThrow().balance() == 100
              && database.history(player, 0, 10).size() == 1;
        } catch (Exception error) {
          throw new AssertionError(error);
        }
      });
      wallet.close();
    }
  }

  @Test
  void adminCommitThenThrowDoesNotDuplicate() throws Exception {
    runAmbiguousAdmin(MutationKind.CREDIT, TransactionType.ADMIN_GIVE, 100, 0, 100);
  }

  @Test
  void adminTakeCommitThenThrowDoesNotDuplicate() throws Exception {
    runAmbiguousAdmin(MutationKind.DEBIT, TransactionType.ADMIN_TAKE, 50, 100, 50);
  }

  @Test
  void adminSetCommitThenThrowDoesNotDuplicate() throws Exception {
    runAmbiguousAdmin(MutationKind.SET, TransactionType.ADMIN_SET, 500, 100, 500);
  }

  @Test
  void pendingAdminBlocksSecondMutation() throws Exception {
    try (SQLiteStorageProvider database = database("admin-block.db")) {
      UUID player = UUID.randomUUID();
      CommitThenThrowStorage ambiguous = new CommitThenThrowStorage(database);
      WalletService wallet = wallet(ambiguous, temp.resolve("admin-block-recovery"));
      wallet.load(player, "Antonio").join();
      EconomyResult first = wallet.administrativeMutation(player, MutationKind.CREDIT, 100,
          TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
      EconomyResult retry = wallet.administrativeMutation(player, MutationKind.CREDIT, 100,
          TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
      assertThat(first.status()).isEqualTo(EconomyResult.Status.ADMIN_PENDING);
      assertThat(retry.status()).isEqualTo(EconomyResult.Status.ADMIN_ALREADY_PENDING);
      assertThat(retry.operationId()).isEqualTo(first.operationId());
      assertThat(ambiguous.applyCalls()).isOne();
      wallet.replayRecovery().join();
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(100);
      assertThat(database.history(player, 0, 10)).hasSize(1);
      wallet.close();
    }
  }

  @Test
  void pendingAdminSurvivesRestart() throws Exception {
    try (SQLiteStorageProvider database = database("admin-restart.db")) {
      UUID player = UUID.randomUUID();
      Path recovery = temp.resolve("admin-restart-recovery");
      WalletService first = wallet(new CommitThenThrowStorage(database), recovery);
      first.load(player, "Antonio").join();
      EconomyResult ambiguous = first.administrativeMutation(player, MutationKind.CREDIT, 100,
          TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
      assertThat(ambiguous.status()).isEqualTo(EconomyResult.Status.ADMIN_PENDING);
      first.close();

      WalletService restarted = wallet(database, recovery);
      restarted.load(player, "Antonio").join();
      EconomyResult retry = restarted.administrativeMutation(player, MutationKind.CREDIT, 100,
          TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
      assertThat(retry.status()).isEqualTo(EconomyResult.Status.ADMIN_ALREADY_PENDING);
      assertThat(retry.operationId()).isEqualTo(ambiguous.operationId());
      restarted.replayRecovery().join();
      assertThat(restarted.pendingAdministrativeOperations()).isZero();
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(100);
      restarted.close();
    }
  }

  @Test
  void pendingAdminPreservesAccountSequence() throws Exception {
    try (SQLiteStorageProvider database = database("admin-sequence.db")) {
      UUID player = UUID.randomUUID();
      database.applyOperation(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 1,
          TransactionType.PLAYTIME_REWARD, 7));
      WalletService wallet = wallet(new CommitThenThrowStorage(database),
          temp.resolve("admin-sequence-recovery"));
      wallet.load(player, "Antonio").join();
      EconomyResult pending = wallet.administrativeMutation(player, MutationKind.SET, 100,
          TransactionType.ADMIN_SET, "ADMIN_SET", "admin:CONSOLE").join();
      long sequence = database.findTransaction(pending.operationId()).orElseThrow().accountSequence();
      assertThat(sequence).isGreaterThan(7);
      wallet.replayRecovery().join();
      wallet.close();
    }
  }

  @Test
  void rewardAfterPendingAdminRunsInOrder() throws Exception {
    try (SQLiteStorageProvider database = database("admin-reward-order.db")) {
      UUID player = UUID.randomUUID();
      WalletService wallet = wallet(new CommitThenThrowStorage(database),
          temp.resolve("admin-reward-order-recovery"));
      wallet.load(player, "Antonio").join();
      EconomyResult admin = wallet.administrativeMutation(player, MutationKind.SET, 100,
          TransactionType.ADMIN_SET, "ADMIN_SET", "admin:CONSOLE").join();
      EconomyResult reward = wallet.credit(player, 10, TransactionType.PLAYTIME_REWARD,
          "SESSION_INTERVAL", "session:test:1").join();
      assertThat(admin.status()).isEqualTo(EconomyResult.Status.ADMIN_PENDING);
      assertThat(reward.status()).isEqualTo(EconomyResult.Status.RECOVERY_PENDING);
      wallet.replayRecovery().join();
      List<GemTransaction> history = database.history(player, 0, 10);
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(110);
      assertThat(history.stream().map(GemTransaction::accountSequence).sorted().toList())
          .isEqualTo(history.stream().map(GemTransaction::accountSequence).toList().reversed());
      wallet.close();
    }
  }

  @Test
  void purchaseAfterPendingAdminRunsInOrder() throws Exception {
    try (SQLiteStorageProvider database = database("admin-purchase-order.db")) {
      UUID player = UUID.randomUUID();
      database.applyOperation(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 200,
          TransactionType.PLAYTIME_REWARD, 1));
      WalletService wallet = wallet(new CommitThenThrowStorage(database),
          temp.resolve("admin-purchase-order-recovery"));
      wallet.load(player, "Antonio").join();
      wallet.administrativeMutation(player, MutationKind.DEBIT, 50,
          TransactionType.ADMIN_TAKE, "ADMIN_TAKE", "admin:CONSOLE").join();
      EconomyResult purchase = wallet.debitForDelivery(player, 100, TransactionType.SHOP_PURCHASE,
          "SHOP_PURCHASE", "reward", true).join();
      assertThat(purchase.status()).isEqualTo(EconomyResult.Status.RECOVERY_PENDING);
      wallet.replayRecovery().join();
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(50);
      wallet.close();
    }
  }

  @Test
  void fullCompletedRewardBufferDisconnectReconnectNoLoss() throws Exception {
    PendingCompletedRewards pending = new PendingCompletedRewards(1);
    UUID other = UUID.randomUUID();
    try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
      reservation.commit(List.of(CompletedReward.create(other, 10, Instant.EPOCH,
          UUID.randomUUID(), 1)));
    }
    assertThat(pending.primarySaturated()).isTrue();
    assertThat(pending.isFull()).isFalse();

    UUID player = UUID.randomUUID();
    PlayerSession oldSession = new PlayerSession(player, 0);
    long interval = Duration.ofMinutes(30).toNanos();
    try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
      int completed = oldSession.timer().update(interval + 1_000_000, interval, false,
          reservation.slots());
      reservation.commit(oldSession.materializeCompleted(completed, 10, Instant.now()));
    }
    assertThat(pending.isFull()).isTrue();
    SessionRegistry sessions = new SessionRegistry();
    sessions.connect(player, interval + 2_000_000);
    assertThat(sessions.get(player).orElseThrow().timer().elapsedNanos()).isZero();
    CompletedReward reward = pending.claim(10).stream()
        .filter(value -> value.playerUuid().equals(player)).findFirst().orElseThrow();

    try (SQLiteStorageProvider database = database("reward-reconnect.db")) {
      database.applyOperation(rewardOperation(reward, 1));
      database.applyOperation(rewardOperation(reward, 1));
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(10);
      assertThat(database.history(player, 0, 10)).hasSize(1);
    }
  }

  @Test
  void journalPendingCountDoesNotScanFilesystem() throws Exception {
    CountingMemoryJournal journal = new CountingMemoryJournal();
    JournalWriter writer = new JournalWriter(journal, 5_100, ignored -> {});
    writer.ready().join();
    UUID player = UUID.randomUUID();
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (int index = 1; index <= 5_000; index++) {
      JournalWriter.StoreSubmission submission = writer.store(operation(
          UUID.randomUUID(), player, MutationKind.CREDIT, 1,
          TransactionType.PLAYTIME_REWARD, index));
      assertThat(submission.accepted()).isTrue();
      writes.add(submission.durable());
    }
    CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
    assertThat(writer.metrics().pending()).isEqualTo(5_000);
    assertThat(journal.directoryScans()).isOne();
    writer.close();
  }

  @Test
  void recoveryBatchDoesNotSortEntireBacklog() throws Exception {
    IndexedJournal journal = new IndexedJournal(10_000);
    journal.initialize();
    assertThat(journal.loadBatch(250)).hasSize(250);
    assertThat(journal.lastBatchTraversalSteps()).isEqualTo(250);
  }

  @Test
  void recoveryBatchIsFairAcrossAccounts() throws Exception {
    IndexedJournal journal = new IndexedJournal(0);
    UUID busy = new UUID(0, 1);
    UUID quiet = new UUID(0, 2);
    for (int sequence = 1; sequence <= 10_000; sequence++) {
      journal.add(operation(UUID.randomUUID(), busy, MutationKind.CREDIT, 1,
          TransactionType.PLAYTIME_REWARD, sequence));
    }
    journal.add(operation(UUID.randomUUID(), quiet, MutationKind.CREDIT, 1,
        TransactionType.PLAYTIME_REWARD, 1));
    assertThat(journal.loadBatch(2).stream().map(EconomyOperation::accountId))
        .containsExactly(busy, quiet);
  }

  @Test
  void shutdownKeepsJournalWriterAliveUntilDatabaseDrain() throws Exception {
    List<String> events = new CopyOnWriteArrayList<>();
    try (SQLiteStorageProvider database = database("shutdown-order.db")) {
      RecordingStorage slow = new RecordingStorage(database, events);
      RecordingJournal journal = new RecordingJournal(events);
      WalletService wallet = new WalletService(slow, temp.resolve("unused"), ignored -> {},
          32, 8, 32, 1, 2, 32, 250, 30, 300, journal);
      UUID player = UUID.randomUUID();
      wallet.load(player, "Shutdown").join();
      CompletableFuture<EconomyResult> mutation = wallet.credit(player, 10,
          TransactionType.PLAYTIME_REWARD, "test", "test");
      WalletService.ShutdownReport report = wallet.closeUntil(
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      assertThat(mutation.join().success()).isTrue();
      assertThat(report.clean()).isTrue();
      assertThat(events.indexOf("db-complete")).isLessThan(events.indexOf("journal-remove"));
      assertThat(events.indexOf("journal-remove")).isLessThan(events.indexOf("journal-close"));
    }
  }

  @Test
  void shutdownDoesNotLoseCompletedReward() throws Exception {
    try (SQLiteStorageProvider database = database("shutdown-reward.db")) {
      WalletService wallet = wallet(database, temp.resolve("shutdown-reward-recovery"));
      UUID player = UUID.randomUUID();
      wallet.load(player, "Reward").join();
      PendingCompletedRewards pending = new PendingCompletedRewards(1);
      PlayerSession session = new PlayerSession(player, 0);
      try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
        reservation.commit(session.materializeCompleted(1, 10, Instant.now()));
      }
      CompletedReward reward = pending.claim(1).getFirst();
      WalletService.OperationSubmission submission = wallet.capture(rewardOperation(reward, 0));
      submission.durability().whenComplete((ignored, error) -> {
        if (error == null) pending.durable(reward.operationId());
        else pending.retry(reward.operationId());
      });
      WalletService.ShutdownReport report = wallet.closeUntil(
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      assertThat(report.clean()).isTrue();
      assertThat(pending.size()).isZero();
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(10);
    }
  }

  private void runAmbiguousAdmin(MutationKind kind, TransactionType type, long amount,
      long initial, long expected) throws Exception {
    try (SQLiteStorageProvider database = database("ambiguous-" + type + ".db")) {
      UUID player = UUID.randomUUID();
      if (initial > 0) database.applyOperation(operation(UUID.randomUUID(), player,
          MutationKind.CREDIT, initial, TransactionType.PLAYTIME_REWARD, 1));
      CommitThenThrowStorage ambiguous = new CommitThenThrowStorage(database);
      WalletService wallet = wallet(ambiguous, temp.resolve("recovery-" + type));
      wallet.load(player, "Antonio").join();
      EconomyResult first = wallet.administrativeMutation(player, kind, amount, type,
          type.name(), "admin:CONSOLE").join();
      EconomyResult retry = wallet.administrativeMutation(player, kind, amount, type,
          type.name(), "admin:CONSOLE").join();
      assertThat(first.status()).isEqualTo(EconomyResult.Status.ADMIN_PENDING);
      assertThat(retry.operationId()).isEqualTo(first.operationId());
      wallet.replayRecovery().join();
      assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(expected);
      assertThat(database.history(player, 0, 20).stream()
          .filter(transaction -> transaction.operationId().equals(first.operationId()))).hasSize(1);
      wallet.close();
    }
  }

  private WalletService wallet(StorageProvider storage, Path recovery) {
    return new WalletService(storage, recovery, ignored -> {}, 128, 32, 256,
        1, 2, 512, 250, 30, 300);
  }

  private SQLiteStorageProvider database(String name) throws Exception {
    SQLiteStorageProvider database = new SQLiteStorageProvider(temp.resolve(name));
    database.initialize();
    return database;
  }

  private static EconomyOperation rewardOperation(CompletedReward reward, long sequence) {
    return new EconomyOperation(reward.operationId(), reward.playerUuid(), MutationKind.CREDIT,
        reward.amount(), TransactionType.PLAYTIME_REWARD, "SESSION_INTERVAL", reward.reference(),
        TransactionStatus.COMMITTED, reward.completedAt(), sequence);
  }

  private static EconomyOperation operation(UUID id, UUID player, MutationKind kind, long amount,
      TransactionType type, long sequence) {
    return new EconomyOperation(id, player, kind, amount, type, "test", "test",
        TransactionStatus.COMMITTED, Instant.now(), sequence);
  }

  private static class DelegatingStorage implements StorageProvider {
    final StorageProvider delegate;
    DelegatingStorage(StorageProvider delegate) { this.delegate = delegate; }
    public void initialize() throws Exception { delegate.initialize(); }
    public Optional<PlayerAccount> loadAccount(UUID id) throws Exception { return delegate.loadAccount(id); }
    public Optional<UUID> findUuidByName(String name) throws Exception { return delegate.findUuidByName(name); }
    public PlayerAccount loadOrCreate(UUID id, String name) throws Exception { return delegate.loadOrCreate(id, name); }
    public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception {
      return delegate.applyOperation(operation);
    }
    public void markDelivery(UUID id, TransactionStatus status, String error) throws Exception {
      delegate.markDelivery(id, status, error);
    }
    public Optional<GemTransaction> findTransaction(UUID id) throws Exception {
      return delegate.findTransaction(id);
    }
    public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception {
      return delegate.leaderboard(offset, limit);
    }
    public List<GemTransaction> history(UUID id, int offset, int limit) throws Exception {
      return delegate.history(id, offset, limit);
    }
    public List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception {
      return delegate.deliveryFailures(offset, limit);
    }
    public long countTransactions(TransactionStatus status) throws Exception {
      return delegate.countTransactions(status);
    }
    public long maxAccountSequence(UUID accountId) throws Exception {
      return delegate.maxAccountSequence(accountId);
    }
    public int workerThreads() { return 1; }
    public String description() { return "test"; }
    public void close() {}
  }

  private static final class CommitThenThrowStorage extends DelegatingStorage {
    private final AtomicBoolean throwAfterCommit = new AtomicBoolean(true);
    private final AtomicInteger calls = new AtomicInteger();
    CommitThenThrowStorage(StorageProvider delegate) { super(delegate); }
    @Override public DurableMutationResult applyOperation(EconomyOperation operation)
        throws Exception {
      calls.incrementAndGet();
      DurableMutationResult committed = delegate.applyOperation(operation);
      if (throwAfterCommit.compareAndSet(true, false)) {
        throw new IllegalStateException("connection lost after COMMIT");
      }
      return committed;
    }
    int applyCalls() { return calls.get(); }
  }

  private static final class CommitFirstAttemptThenThrowStorage extends DelegatingStorage {
    private final CountDownLatch release;
    private final java.util.Set<UUID> firstAttempts = ConcurrentHashMap.newKeySet();
    CommitFirstAttemptThenThrowStorage(StorageProvider delegate, CountDownLatch release) {
      super(delegate);
      this.release = release;
    }
    @Override public DurableMutationResult applyOperation(EconomyOperation operation)
        throws Exception {
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
      DurableMutationResult committed = delegate.applyOperation(operation);
      if (firstAttempts.add(operation.operationId())) {
        throw new IllegalStateException("connection lost after COMMIT");
      }
      return committed;
    }
  }

  private static final class RecordingStorage extends DelegatingStorage {
    private final List<String> events;
    RecordingStorage(StorageProvider delegate, List<String> events) {
      super(delegate);
      this.events = events;
    }
    @Override public DurableMutationResult applyOperation(EconomyOperation operation)
        throws Exception {
      DurableMutationResult result = delegate.applyOperation(operation);
      Thread.sleep(50);
      events.add("db-complete");
      return result;
    }
  }

  private static class CountingMemoryJournal extends RecoveryJournal {
    final Map<UUID, EconomyOperation> operations = new LinkedHashMap<>();
    private final AtomicInteger scans = new AtomicInteger();
    CountingMemoryJournal() { super(Path.of("counting-memory")); }
    @Override public synchronized void initialize() { scans.incrementAndGet(); }
    @Override public synchronized void store(EconomyOperation operation) {
      operations.putIfAbsent(operation.operationId(), operation);
    }
    @Override public synchronized void remove(UUID operationId) { operations.remove(operationId); }
    @Override public synchronized List<EconomyOperation> loadBatch(int limit) {
      return operations.values().stream().limit(limit).toList();
    }
    @Override public synchronized List<EconomyOperation> loadAll() {
      return List.copyOf(operations.values());
    }
    @Override public synchronized int pendingCount() { return operations.size(); }
    @Override public synchronized int pendingCount(UUID accountId) {
      return (int) operations.values().stream()
          .filter(operation -> operation.accountId().equals(accountId)).count();
    }
    @Override public synchronized int corruptCount() { return 0; }
    int directoryScans() { return scans.get(); }
  }

  private static final class RecordingJournal extends CountingMemoryJournal {
    private final List<String> events;
    RecordingJournal(List<String> events) { this.events = events; }
    @Override public synchronized void remove(UUID operationId) {
      super.remove(operationId);
      events.add("journal-remove");
    }
    @Override public void close() { events.add("journal-close"); }
  }

  private static final class IndexedJournal extends RecoveryJournal {
    private final int accounts;
    IndexedJournal(int accounts) {
      super(Path.of("indexed-memory"));
      this.accounts = accounts;
    }
    @Override public synchronized void initialize() {
      for (int index = 0; index < accounts; index++) {
        UUID account = new UUID(0, index + 1L);
        add(operation(UUID.randomUUID(), account, MutationKind.CREDIT, 1,
            TransactionType.PLAYTIME_REWARD, 1));
      }
    }
    void add(EconomyOperation operation) { index(operation); }
  }
}
