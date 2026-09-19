package net.watones.novagems.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import net.watones.novagems.storage.SQLiteStorageProvider;
import net.watones.novagems.storage.DurableMutationResult;
import net.watones.novagems.storage.StorageProvider;
import net.watones.novagems.storage.RecoveryJournal;
import net.watones.novagems.activity.ActivitySignal;
import net.watones.novagems.activity.RollingActivityProfile;
import net.watones.novagems.session.SessionAccumulator;
import net.watones.novagems.session.PendingCompletedRewards;
import net.watones.novagems.session.PlayerSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class WalletLoadSimulationTest {
  @org.junit.jupiter.api.Test
  void fiveHundredPlayersPauseSafelyUnderJournalBackpressure() {
    PendingCompletedRewards pending = new PendingCompletedRewards(400);
    long interval = java.time.Duration.ofMinutes(30).toNanos();
    List<PlayerSession> sessions = new ArrayList<>();
    int completed = 0;
    int paused = 0;
    for (int index = 0; index < 500; index++) {
      PlayerSession session = new PlayerSession(UUID.randomUUID(), 0);
      sessions.add(session);
      boolean backpressure = pending.isFull();
      try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
        int cycles = session.timer().update(interval + 1, interval, backpressure,
            reservation.slots());
        reservation.commit(session.materializeCompleted(cycles, 10, Instant.now()));
        completed += cycles;
        if (backpressure) paused++;
      }
    }
    assertThat(pending.size()).isEqualTo(pending.capacity()).isEqualTo(450);
    assertThat(completed).isEqualTo(450);
    assertThat(paused).isEqualTo(50);

    for (var reward : pending.claim(1_000)) pending.durable(reward.operationId());
    for (int index = 450; index < 500; index++) {
      PlayerSession session = sessions.get(index);
      try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
        int cycles = session.timer().update(interval * 2 + 2, interval, false,
            reservation.slots());
        reservation.commit(session.materializeCompleted(cycles, 10, Instant.now()));
      }
    }
    assertThat(pending.size()).isEqualTo(50);
  }

  @org.junit.jupiter.api.Test
  void fiveHundredCompletedRewardsRecoverExactlyOnceWithoutPlayerThreads(@TempDir Path temp)
      throws Exception {
    SQLiteStorageProvider database = new SQLiteStorageProvider(temp.resolve("outage-load.db"));
    database.initialize();
    ToggleStorage storage = new ToggleStorage(database);
    WalletService wallets =
        new WalletService(storage, temp.resolve("outage-recovery"), ignored -> {}, 2048, 8, 4096);
    List<UUID> ids = new ArrayList<>(500);
    List<CompletableFuture<PlayerAccount>> loads = new ArrayList<>(500);
    for (int index = 0; index < 500; index++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      loads.add(wallets.load(id, "R" + index));
    }
    CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();

    storage.available = false;
    List<CompletableFuture<EconomyResult>> captures = new ArrayList<>(500);
    for (UUID id : ids) {
      captures.add(wallets.credit(id, 10, TransactionType.PLAYTIME_REWARD, "cycle", "load"));
    }
    CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).join();
    assertThat(captures).allMatch(result -> result.join().recoveryPending());
    assertThat(new RecoveryJournal(temp.resolve("outage-recovery")).pendingCount()).isEqualTo(500);
    assertThat(wallets.metrics().executorQueueSize()).isLessThanOrEqualTo(2048);

    storage.available = true;
    assertThat(wallets.replayRecovery().join().recovered()).isEqualTo(250);
    assertThat(wallets.replayRecovery().join().recovered()).isEqualTo(250);
    assertThat(ids).allMatch(id -> {
      try {
        return database.loadAccount(id).orElseThrow().balance() == 10
            && database.history(id, 0, 10).size() == 1;
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    });
    long novaThreads = Thread.getAllStackTraces().keySet().stream()
        .map(Thread::getName).filter(name -> name.startsWith("NovaGems-")).count();
    assertThat(novaThreads).isLessThanOrEqualTo(3);
    wallets.close();
    database.close();
  }

  @ParameterizedTest(name = "{0} concurrent accounts")
  @ValueSource(ints = {100, 250, 500})
  void boundedSQLiteWriterPreservesEveryBalance(int players, @TempDir Path temp) throws Exception {
    SQLiteStorageProvider storage = new SQLiteStorageProvider(temp.resolve("load.db"));
    storage.initialize();
    WalletService wallets =
        new WalletService(storage, temp.resolve("recovery"), error -> {}, 2048, 8, 4096);
    List<UUID> ids = new ArrayList<>(players);
    List<CompletableFuture<PlayerAccount>> loads = new ArrayList<>(players);
    for (int index = 0; index < players; index++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      loads.add(wallets.load(id, "P" + index));
    }
    CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();

    List<CompletableFuture<EconomyResult>> credits = new ArrayList<>(players);
    for (UUID id : ids) {
      credits.add(wallets.credit(id, 1, TransactionType.ADMIN_GIVE, "load", "simulation"));
    }
    CompletableFuture.allOf(credits.toArray(CompletableFuture[]::new)).join();
    assertThat(credits).allMatch(future -> future.join().success());
    assertThat(ids).allMatch(id -> wallets.account(id).orElseThrow().balance() == 1);
    long interval = java.time.Duration.ofMinutes(30).toNanos();
    for (int player = 0; player < players; player++) {
      SessionAccumulator session = new SessionAccumulator(0);
      RollingActivityProfile activity = new RollingActivityProfile(0, 128);
      for (int second = 1; second <= 120; second++) {
        activity.add(ActivitySignal.NORMAL, second + player, second * 1_000_000_000L);
        wallets.account(ids.get(player)).orElseThrow().balance();
      }
      assertThat(session.update(interval + 1, interval, false)).isEqualTo(1);
      assertThat(activity.size()).isEqualTo(120);
    }
    long novaThreads =
        Thread.getAllStackTraces().keySet().stream()
            .map(Thread::getName)
            .filter(name -> name.startsWith("NovaGems-"))
            .count();
    assertThat(novaThreads).isLessThanOrEqualTo(3);
    wallets.close();
    storage.close();
  }

  private static final class ToggleStorage implements StorageProvider {
    private final StorageProvider delegate;
    private volatile boolean available = true;
    private ToggleStorage(StorageProvider delegate) { this.delegate = delegate; }
    private void check() { if (!available) throw new IllegalStateException("offline"); }
    public void initialize() throws Exception { delegate.initialize(); }
    public Optional<PlayerAccount> loadAccount(UUID uuid) throws Exception { check(); return delegate.loadAccount(uuid); }
    public Optional<UUID> findUuidByName(String name) throws Exception { check(); return delegate.findUuidByName(name); }
    public PlayerAccount loadOrCreate(UUID uuid, String name) throws Exception { check(); return delegate.loadOrCreate(uuid, name); }
    public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception { check(); return delegate.applyOperation(operation); }
    public void markDelivery(UUID id, TransactionStatus status, String error) throws Exception { check(); delegate.markDelivery(id, status, error); }
    public Optional<GemTransaction> findTransaction(UUID id) throws Exception { check(); return delegate.findTransaction(id); }
    public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception { check(); return delegate.leaderboard(offset, limit); }
    public List<GemTransaction> history(UUID uuid, int offset, int limit) throws Exception { check(); return delegate.history(uuid, offset, limit); }
    public List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception { check(); return delegate.deliveryFailures(offset, limit); }
    public long countTransactions(TransactionStatus status) throws Exception { check(); return delegate.countTransactions(status); }
    public int workerThreads() { return 1; }
    public String description() { return "toggle"; }
    public void close() {}
  }
}
