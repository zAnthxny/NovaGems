package net.watones.novagems.storage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Consumer;
import net.watones.novagems.economy.EconomyOperation;

/** Single bounded worker owning every production filesystem access to the recovery journal. */
public final class JournalWriter implements AutoCloseable {
  private final RecoveryJournal journal;
  private final ThreadPoolExecutor worker;
  private final int capacity;
  private final Consumer<Throwable> errorSink;
  private final CompletableFuture<Void> ready = new CompletableFuture<>();
  private final AtomicInteger writing = new AtomicInteger();
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicInteger corrupt = new AtomicInteger();
  private final ConcurrentHashMap<UUID, AtomicInteger> pendingAccounts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, UUID> operationAccounts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ConcurrentSkipListSet<Long>> pendingSequences =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> operationSequences = new ConcurrentHashMap<>();
  private final LongAdder failures = new LongAdder();
  private final LongAdder writes = new LongAdder();
  private final AtomicLong totalLatencyNanos = new AtomicLong();
  private volatile JournalHealth health = JournalHealth.INITIALIZING;
  private volatile boolean accepting = true;
  private volatile Consumer<String> debug = ignored -> {};

  public JournalWriter(RecoveryJournal journal, int capacity, Consumer<Throwable> errorSink) {
    if (capacity < 1) throw new IllegalArgumentException("Journal queue capacity must be positive");
    this.journal = journal;
    this.capacity = capacity;
    this.errorSink = errorSink;
    worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity),
        Thread.ofPlatform().name("NovaGems-JournalWriter").factory(),
        new ThreadPoolExecutor.AbortPolicy());
    worker.execute(() -> {
      try {
        journal.initialize();
        rebuildIndex();
        if (corrupt.get() > 0) {
          errorSink.accept(new IllegalStateException(
              "SEVERE: " + corrupt.get() + " corrupt recovery record(s) quarantined"));
        }
        transition(JournalHealth.HEALTHY);
        ready.complete(null);
      } catch (Throwable failure) {
        failures.increment();
        transition(JournalHealth.UNAVAILABLE);
        ready.completeExceptionally(failure);
        errorSink.accept(failure);
      }
    });
  }

  public StoreSubmission store(EconomyOperation operation) {
    CompletableFuture<Void> durable = new CompletableFuture<>();
    if (!accepting || health == JournalHealth.SHUTTING_DOWN) {
      durable.completeExceptionally(new IllegalStateException("Journal writer is shutting down"));
      return new StoreSubmission(false, durable);
    }
    try {
      worker.execute(() -> performStore(operation, durable));
      return new StoreSubmission(true, durable);
    } catch (RejectedExecutionException saturated) {
      transition(JournalHealth.BACKPRESSURE);
      durable.completeExceptionally(saturated);
      return new StoreSubmission(false, durable);
    }
  }

  private void performStore(EconomyOperation operation, CompletableFuture<Void> durable) {
    long started = System.nanoTime();
    writing.incrementAndGet();
    try {
      journal.store(operation);
      pending.set(journal.pendingCount());
      if (operationAccounts.putIfAbsent(operation.operationId(), operation.accountId()) == null) {
        pendingAccounts.computeIfAbsent(operation.accountId(), ignored -> new AtomicInteger())
            .incrementAndGet();
        operationSequences.put(operation.operationId(), operation.accountSequence());
        pendingSequences.computeIfAbsent(operation.accountId(),
            ignored -> new ConcurrentSkipListSet<>()).add(operation.accountSequence());
      }
      writes.increment();
      durable.complete(null);
      if (accepting && health != JournalHealth.HEALTHY
          && worker.getQueue().remainingCapacity() > 0) {
        transition(JournalHealth.HEALTHY);
      }
    } catch (Throwable failure) {
      failures.increment();
      transition(JournalHealth.UNAVAILABLE);
      durable.completeExceptionally(failure);
      errorSink.accept(failure);
    } finally {
      totalLatencyNanos.addAndGet(System.nanoTime() - started);
      writing.decrementAndGet();
    }
  }

  public CompletableFuture<Void> remove(UUID operationId) {
    return submit(() -> {
      // The in-memory index is authoritative for ordering while the durable record exists.
      // Never make it look removed before the filesystem deletion has actually succeeded.
      UUID accountId = operationAccounts.get(operationId);
      Long sequence = operationSequences.get(operationId);
      journal.remove(operationId);
      pending.set(journal.pendingCount());
      if (accountId != null) operationAccounts.remove(operationId, accountId);
      if (sequence != null) operationSequences.remove(operationId, sequence);
      if (accountId != null) {
        pendingAccounts.computeIfPresent(accountId,
            (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
        if (sequence != null) {
          pendingSequences.computeIfPresent(accountId, (ignored, sequences) -> {
            sequences.remove(sequence);
            return sequences.isEmpty() ? null : sequences;
          });
        }
      }
      return null;
    });
  }

  public CompletableFuture<List<EconomyOperation>> loadBatch(int limit) {
    return submit(() -> {
      List<EconomyOperation> batch = journal.loadBatch(limit);
      int actualPending = journal.pendingCount();
      if (pending.get() != actualPending) rebuildIndex();
      return batch;
    });
  }

  public CompletableFuture<List<EconomyOperation>> loadAll() {
    return submit(journal::loadAll);
  }

  public CompletableFuture<List<CorruptRecoveryRecord>> corruptRecords() {
    return submit(journal::corruptRecords);
  }

  /** Low-frequency reconciliation for external filesystem changes; always runs on the owner. */
  public CompletableFuture<Void> reconcile() {
    return submit(() -> {
      journal.initialize();
      rebuildIndex();
      return null;
    });
  }

  private void rebuildIndex() throws Exception {
    pendingAccounts.clear();
    operationAccounts.clear();
    pendingSequences.clear();
    operationSequences.clear();
    pending.set(journal.pendingCount());
    corrupt.set(journal.corruptCount());
    for (EconomyOperation operation : journal.loadAll()) {
      operationAccounts.put(operation.operationId(), operation.accountId());
      pendingAccounts.computeIfAbsent(operation.accountId(), ignored -> new AtomicInteger())
          .incrementAndGet();
      operationSequences.put(operation.operationId(), operation.accountSequence());
      pendingSequences.computeIfAbsent(operation.accountId(),
          ignored -> new ConcurrentSkipListSet<>()).add(operation.accountSequence());
    }
  }

  public CompletableFuture<Void> ready() { return ready; }
  public JournalHealth health() { return health; }
  public int queueSize() { return worker.getQueue().size(); }
  public int queueCapacity() { return capacity; }
  public int writing() { return writing.get(); }
  public int pendingForAccount(UUID accountId) {
    AtomicInteger count = pendingAccounts.get(accountId);
    return count == null ? 0 : count.get();
  }
  public boolean hasUnsettledEarlier(UUID accountId, long sequence, long settledSequence) {
    ConcurrentSkipListSet<Long> sequences = pendingSequences.get(accountId);
    if (sequences == null) return false;
    Long firstUnsettled = sequences.higher(settledSequence);
    return firstUnsettled != null && firstUnsettled < sequence;
  }
  public long maxSequence(UUID accountId) {
    ConcurrentSkipListSet<Long> sequences = pendingSequences.get(accountId);
    return sequences == null || sequences.isEmpty() ? 0 : sequences.last();
  }

  public Metrics metrics() {
    long count = writes.sum();
    long averageMicros = count == 0 ? 0 : totalLatencyNanos.get() / count / 1_000;
    return new Metrics(queueSize(), capacity, writing(), failures.sum(), averageMicros,
        pending.get(), corrupt.get());
  }

  public void onDebug(Consumer<String> logger) { debug = logger == null ? ignored -> {} : logger; }

  private <T> CompletableFuture<T> submit(ThrowingSupplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      worker.execute(() -> {
        try {
          T value = supplier.get();
          if (accepting && health != JournalHealth.HEALTHY) transition(JournalHealth.HEALTHY);
          future.complete(value);
        } catch (Throwable failure) {
          failures.increment();
          transition(JournalHealth.UNAVAILABLE);
          errorSink.accept(failure);
          future.completeExceptionally(failure);
        }
      });
    } catch (RejectedExecutionException rejected) {
      transition(JournalHealth.BACKPRESSURE);
      future.completeExceptionally(rejected);
    }
    return future;
  }

  private void transition(JournalHealth next) {
    JournalHealth previous = health;
    health = next;
    if (previous != next) debug.accept("journal health transition " + previous + " -> " + next);
  }

  /** Enqueues a barrier without stopping the writer. DB workers may still enqueue removals later. */
  public boolean awaitIdle(long deadlineNanos) {
    CompletableFuture<Void> barrier = submit(() -> null);
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) return false;
    try {
      barrier.get(remaining, TimeUnit.NANOSECONDS);
      return true;
    } catch (Exception timeoutOrFailure) {
      return false;
    }
  }

  public boolean drainAndClose(long timeout, TimeUnit unit) {
    accepting = false;
    transition(JournalHealth.SHUTTING_DOWN);
    worker.shutdown();
    try {
      boolean drained = worker.awaitTermination(timeout, unit);
      if (!drained) worker.shutdownNow();
      if (drained) journal.close();
      return drained;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      worker.shutdownNow();
      return false;
    } catch (Exception closeFailure) {
      failures.increment();
      errorSink.accept(closeFailure);
      return false;
    }
  }

  @Override public void close() { drainAndClose(5, TimeUnit.SECONDS); }

  public record StoreSubmission(boolean accepted, CompletableFuture<Void> durable) {}
  public record Metrics(int queueSize, int queueCapacity, int writing, long writeFailures,
                        long averageWriteLatencyMicros, int pending, int corrupt) {}
  @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
}
