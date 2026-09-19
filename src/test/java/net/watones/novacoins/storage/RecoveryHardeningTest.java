package net.watones.novacoins.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.watones.novacoins.economy.CoinTransaction;
import net.watones.novacoins.economy.EconomyOperation;
import net.watones.novacoins.economy.EconomyResult;
import net.watones.novacoins.economy.MutationKind;
import net.watones.novacoins.economy.PlayerAccount;
import net.watones.novacoins.economy.TransactionStatus;
import net.watones.novacoins.economy.TransactionType;
import net.watones.novacoins.economy.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryHardeningTest {
  @TempDir Path temp;

  @Test
  void oneLogicalRewardRetriedTenTimesCreditsOnce() throws Exception {
    SQLiteStorageProvider database = database("logical.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Logical");
    ToggleStorage storage = new ToggleStorage(database);
    WalletService wallet = wallet(storage, "logical-recovery");
    wallet.load(player, "Logical").join();
    EconomyOperation reward = credit(player, UUID.randomUUID(), 10);

    storage.available = false;
    for (int attempt = 0; attempt < 10; attempt++) {
      WalletService.OperationSubmission submission = wallet.capture(reward);
      assertThat(submission.captured()).isTrue();
      assertThat(submission.result().join().status()).isEqualTo(EconomyResult.Status.RECOVERY_PENDING);
    }
    assertThat(new RecoveryJournal(temp.resolve("logical-recovery")).pendingCount()).isEqualTo(1);

    storage.available = true;
    assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(1);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(10);
    assertThat(database.history(player, 0, 10)).hasSize(1);
    wallet.close();
  }

  @Test
  void completedRewardCapturedWhileStorageUnavailableSurvivesRestart() throws Exception {
    Path dbFile = temp.resolve("unavailable.db");
    SQLiteStorageProvider database = new SQLiteStorageProvider(dbFile);
    database.initialize();
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Offline");
    ToggleStorage unavailable = new ToggleStorage(database);
    WalletService first = wallet(unavailable, "offline-recovery");
    first.load(player, "Offline").join();
    EconomyOperation reward = credit(player, UUID.randomUUID(), 10);
    unavailable.available = false;
    assertThat(first.capture(reward).captured()).isTrue();
    assertThat(first.capture(reward).result().join().recoveryPending()).isTrue();
    first.close();

    WalletService restarted = wallet(database, "offline-recovery");
    assertThat(restarted.replayRecovery().join().recovered()).isEqualTo(1);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(10);
    assertThat(database.history(player, 0, 10)).hasSize(1);
    restarted.close();
  }

  @Test
  void staleDeliveredJournalIsCleanedWithoutRedelivery() throws Exception {
    assertTerminalJournalIsCleaned(TransactionStatus.DELIVERED);
  }

  @Test
  void staleRefundedJournalIsCleanedWithoutSecondRefund() throws Exception {
    assertTerminalJournalIsCleaned(TransactionStatus.REFUNDED);
  }

  @Test
  void startedDeliveryBecomesManualReviewAndIsNeverReplayable() throws Exception {
    SQLiteStorageProvider storage = database("ambiguous.db");
    UUID player = UUID.randomUUID();
    storage.loadOrCreate(player, "Ambiguous");
    storage.applyOperation(credit(player, UUID.randomUUID(), 100));
    EconomyOperation purchase = purchase(player, UUID.randomUUID(), 25);
    storage.applyOperation(purchase);
    storage.markDelivery(purchase.operationId(), TransactionStatus.DELIVERY_STARTED, "started");
    RecoveryJournal journal = journal("ambiguous-recovery");
    journal.store(purchase);

    WalletService wallet = wallet(storage, "ambiguous-recovery");
    assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(1);
    CoinTransaction durable = storage.findTransaction(purchase.operationId()).orElseThrow();
    assertThat(durable.status()).isEqualTo(TransactionStatus.MANUAL_REVIEW);
    assertThat(wallet.pendingDelivery(purchase.operationId())).isEmpty();
    journal.initialize();
    assertThat(journal.pendingCount()).isZero();
    assertThat(storage.loadAccount(player).orElseThrow().balance()).isEqualTo(75);
    wallet.close();
  }

  @Test
  void deterministicRefundCannotCreditTwice() throws Exception {
    SQLiteStorageProvider storage = database("refund.db");
    UUID player = UUID.randomUUID();
    storage.loadOrCreate(player, "Refund");
    storage.applyOperation(credit(player, UUID.randomUUID(), 100));
    EconomyOperation purchase = purchase(player, UUID.randomUUID(), 40);
    storage.applyOperation(purchase);
    WalletService wallet = wallet(storage, "refund-recovery");
    wallet.load(player, "Refund").join();

    assertThat(wallet.refundPurchase(player, 40, "safe", purchase.operationId()).join().success())
        .isTrue();
    assertThat(wallet.refundPurchase(player, 40, "safe", purchase.operationId()).join().success())
        .isTrue();
    assertThat(storage.loadAccount(player).orElseThrow().balance()).isEqualTo(100);
    assertThat(storage.history(player, 0, 10)).hasSize(3);
    wallet.close();
  }

  @Test
  void pendingPurchaseRemainsBlockedUntilDurableOutcomeIsKnown() throws Exception {
    SQLiteStorageProvider database = database("pending.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Pending");
    database.applyOperation(credit(player, UUID.randomUUID(), 100));
    ToggleStorage storage = new ToggleStorage(database);
    WalletService wallet = wallet(storage, "pending-recovery");
    wallet.load(player, "Pending").join();
    storage.available = false;
    EconomyOperation purchase = purchase(player, UUID.randomUUID(), 25);
    assertThat(wallet.capture(purchase).result().join().recoveryPending()).isTrue();
    for (int click = 0; click < 50; click++) assertThat(wallet.hasPendingPurchase(player)).isTrue();
    assertThat(journal("pending-recovery").pendingCount()).isEqualTo(1);
    wallet.close();
  }

  @Test
  void legacyMultiplePendingPurchasesKeepPlayerBlockedUntilAllAreTerminal() throws Exception {
    SQLiteStorageProvider storage = database("legacy-pending.db");
    UUID player = UUID.randomUUID();
    storage.loadOrCreate(player, "LegacyPending");
    storage.applyOperation(credit(player, UUID.randomUUID(), 100));
    WalletService wallet = wallet(storage, "legacy-pending-recovery");
    wallet.load(player, "LegacyPending").join();
    EconomyOperation first = purchase(player, UUID.randomUUID(), 10);
    EconomyOperation second = purchase(player, UUID.randomUUID(), 10);
    assertThat(wallet.capture(first).result().join().success()).isTrue();
    assertThat(wallet.capture(second).result().join().success()).isTrue();

    wallet.markDelivery(first.operationId(), TransactionStatus.DELIVERY_STARTED, "start").join();
    wallet.markDelivery(first.operationId(), TransactionStatus.DELIVERED, null).join();
    assertThat(wallet.hasPendingPurchase(player)).isTrue();
    wallet.markDelivery(second.operationId(), TransactionStatus.DELIVERY_STARTED, "start").join();
    wallet.markDelivery(second.operationId(), TransactionStatus.DELIVERED, null).join();
    assertThat(wallet.hasPendingPurchase(player)).isFalse();
    wallet.close();
  }

  private void assertTerminalJournalIsCleaned(TransactionStatus terminal) throws Exception {
    String suffix = terminal.name().toLowerCase();
    SQLiteStorageProvider storage = database(suffix + ".db");
    UUID player = UUID.randomUUID();
    storage.loadOrCreate(player, "Terminal");
    storage.applyOperation(credit(player, UUID.randomUUID(), 100));
    EconomyOperation purchase = purchase(player, UUID.randomUUID(), 20);
    storage.applyOperation(purchase);
    if (terminal == TransactionStatus.DELIVERED) {
      storage.markDelivery(purchase.operationId(), TransactionStatus.DELIVERY_STARTED, "started");
      storage.markDelivery(purchase.operationId(), terminal, "terminal");
    } else {
      storage.markDelivery(
          purchase.operationId(), TransactionStatus.DELIVERY_FAILED_SAFE, "safe failure");
      storage.markDelivery(purchase.operationId(), terminal, "terminal");
    }
    RecoveryJournal journal = journal(suffix + "-recovery");
    journal.store(purchase);

    WalletService wallet = wallet(storage, suffix + "-recovery");
    wallet.replayRecovery().join();
    assertThat(storage.loadAccount(player).orElseThrow().balance()).isEqualTo(80);
    assertThat(storage.history(player, 0, 10)).hasSize(2);
    assertThat(wallet.pendingDelivery(purchase.operationId())).isEmpty();
    journal.initialize();
    assertThat(journal.pendingCount()).isZero();
    wallet.close();
  }

  private SQLiteStorageProvider database(String name) throws Exception {
    SQLiteStorageProvider storage = new SQLiteStorageProvider(temp.resolve(name));
    storage.initialize();
    return storage;
  }

  private WalletService wallet(StorageProvider storage, String recovery) {
    return new WalletService(storage, temp.resolve(recovery), ignored -> {}, 1024, 64, 2048, 1, 1);
  }

  private RecoveryJournal journal(String name) throws Exception {
    RecoveryJournal journal = new RecoveryJournal(temp.resolve(name));
    journal.initialize();
    return journal;
  }

  private EconomyOperation credit(UUID player, UUID operation, long amount) {
    return new EconomyOperation(
        operation, player, MutationKind.CREDIT, amount, TransactionType.PLAYTIME_REWARD,
        "SESSION_INTERVAL", "session:test:1", TransactionStatus.COMMITTED, Instant.now());
  }

  private EconomyOperation purchase(UUID player, UUID operation, long amount) {
    return new EconomyOperation(
        operation, player, MutationKind.DEBIT, amount, TransactionType.SHOP_PURCHASE,
        "SHOP_PURCHASE", "key", TransactionStatus.DELIVERY_PENDING, Instant.now());
  }

  private static final class ToggleStorage implements StorageProvider {
    private final StorageProvider delegate;
    private volatile boolean available = true;

    private ToggleStorage(StorageProvider delegate) { this.delegate = delegate; }
    private void check() { if (!available) throw new IllegalStateException("storage unavailable"); }
    public void initialize() throws Exception { delegate.initialize(); }
    public Optional<PlayerAccount> loadAccount(UUID uuid) throws Exception { check(); return delegate.loadAccount(uuid); }
    public Optional<UUID> findUuidByName(String name) throws Exception { check(); return delegate.findUuidByName(name); }
    public PlayerAccount loadOrCreate(UUID uuid, String name) throws Exception { check(); return delegate.loadOrCreate(uuid, name); }
    public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception { check(); return delegate.applyOperation(operation); }
    public void markDelivery(UUID id, TransactionStatus status, String error) throws Exception { check(); delegate.markDelivery(id, status, error); }
    public Optional<CoinTransaction> findTransaction(UUID id) throws Exception { check(); return delegate.findTransaction(id); }
    public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception { check(); return delegate.leaderboard(offset, limit); }
    public List<CoinTransaction> history(UUID uuid, int offset, int limit) throws Exception { check(); return delegate.history(uuid, offset, limit); }
    public List<CoinTransaction> deliveryFailures(int offset, int limit) throws Exception { check(); return delegate.deliveryFailures(offset, limit); }
    public long countTransactions(TransactionStatus status) throws Exception { check(); return delegate.countTransactions(status); }
    public int workerThreads() { return 1; }
    public String description() { return "toggle"; }
    public void close() {}
  }
}
