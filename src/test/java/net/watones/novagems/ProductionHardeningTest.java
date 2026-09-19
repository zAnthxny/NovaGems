package net.watones.novagems;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.watones.novagems.economy.*;
import net.watones.novagems.session.CompletedReward;
import net.watones.novagems.session.PendingCompletedRewards;
import net.watones.novagems.shop.PurchasePolicy;
import net.watones.novagems.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionHardeningTest {
  @TempDir Path temp;

  @Test
  void slowJournalNeverBlocksWalletCaptureCaller() throws Exception {
    SQLiteStorageProvider database = database("slow.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Slow");
    RecoveryJournal slow = new RecoveryJournal(temp.resolve("slow-journal")) {
      @Override public synchronized void store(EconomyOperation operation) throws java.io.IOException {
        try { Thread.sleep(100); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        super.store(operation);
      }
    };
    WalletService wallet = wallet(database, slow, 64, 250);
    wallet.load(player, "Slow").join();
    long started = System.nanoTime();
    WalletService.OperationSubmission submission = wallet.capture(credit(player, 1, 10));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertThat(elapsedMillis).isLessThan(50);
    assertThat(submission.durability().isDone()).isFalse();
    assertThat(submission.result().join().success()).isTrue();
    wallet.close();
    database.close();
  }

  @Test
  void fiveHundredRewardJournalStormUsesOneBoundedWriter() throws Exception {
    RecoveryJournal journal = new RecoveryJournal(temp.resolve("storm"));
    JournalWriter writer = new JournalWriter(journal, 512, ignored -> {});
    writer.ready().join();
    UUID player = UUID.randomUUID();
    List<JournalWriter.StoreSubmission> submissions = new ArrayList<>();
    for (int index = 1; index <= 500; index++) {
      submissions.add(writer.store(credit(player, index, 1)));
    }
    assertThat(submissions).allMatch(JournalWriter.StoreSubmission::accepted);
    assertThat(writer.metrics().queueSize()).isLessThanOrEqualTo(512);
    CompletableFuture.allOf(submissions.stream().map(JournalWriter.StoreSubmission::durable)
        .toArray(CompletableFuture[]::new)).join();
    assertThat(writer.metrics().pending()).isEqualTo(500);
    assertThat(Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().equals("NovaGems-JournalWriter"))).hasSize(1);
    assertThat(writer.drainAndClose(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void recoveryUsesAccountSequenceNotReverseOperationUuid() throws Exception {
    SQLiteStorageProvider database = database("order.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Order");
    RecoveryJournal journal = new RecoveryJournal(temp.resolve("order-journal"));
    journal.initialize();
    journal.store(operation(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), player,
        MutationKind.CREDIT, 10, 1));
    journal.store(operation(UUID.fromString("00000000-0000-0000-0000-000000000001"), player,
        MutationKind.DEBIT, 10, 2));
    WalletService wallet = wallet(database, new RecoveryJournal(temp.resolve("order-journal")), 64, 250);
    assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(2);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isZero();
    wallet.close();
    database.close();
  }

  @Test
  void setGiveTakeRecoveryIsDeterministic() throws Exception {
    SQLiteStorageProvider database = database("order-set.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "OrderSet");
    database.applyOperation(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 100, 1));
    RecoveryJournal journal = new RecoveryJournal(temp.resolve("order-set-journal"));
    journal.initialize();
    journal.store(operation(UUID.randomUUID(), player, MutationKind.SET, 500, 2));
    journal.store(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 50, 3));
    journal.store(operation(UUID.randomUUID(), player, MutationKind.DEBIT, 100, 4));
    WalletService wallet = wallet(database,
        new RecoveryJournal(temp.resolve("order-set-journal")), 64, 250);
    assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(3);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(450);
    wallet.close();
    database.close();
  }

  @Test
  void corruptRecordIsQuarantinedWithoutBlockingValidRecords() throws Exception {
    SQLiteStorageProvider database = database("corrupt.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Corrupt");
    Path directory = temp.resolve("corrupt-journal");
    RecoveryJournal seed = new RecoveryJournal(directory);
    seed.initialize();
    seed.store(credit(player, 1, 2));
    seed.store(credit(player, 2, 3));
    Files.write(directory.resolve("broken.op"), new byte[] {1, 2, 3});
    WalletService wallet = wallet(database, new RecoveryJournal(directory), 64, 250);
    assertThat(wallet.replayRecovery().join().recovered()).isEqualTo(2);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(5);
    assertThat(wallet.metrics().recoveryCorrupt()).isEqualTo(1);
    assertThat(Files.list(directory.resolve("corrupt"))).hasSize(1);
    wallet.close();
    database.close();
  }

  @Test
  void recoveryBatchNeverExceedsConfiguredLimitForTenThousand() {
    MemoryJournal journal = new MemoryJournal();
    UUID player = UUID.randomUUID();
    for (int index = 1; index <= 10_000; index++) journal.operations.add(credit(player, index, 1));
    JournalWriter writer = new JournalWriter(journal, 64, ignored -> {});
    assertThat(writer.loadBatch(250).join()).hasSize(250);
    assertThat(writer.metrics().pending()).isEqualTo(10_000);
    writer.close();
  }

  @Test
  void storageDownStopsRecoveryAfterFirstFailure() {
    MemoryJournal journal = new MemoryJournal();
    UUID player = UUID.randomUUID();
    for (int index = 1; index <= 10_000; index++) journal.operations.add(credit(player, index, 1));
    CountingUnavailableStorage storage = new CountingUnavailableStorage();
    WalletService wallet = wallet(storage, journal, 64, 250);
    assertThat(wallet.replayRecovery().join().recovered()).isZero();
    assertThat(storage.applyCalls).hasValue(1);
    wallet.close();
  }

  @Test
  void administrativeOutageBecomesOneRecoverableStableOperation() throws Exception {
    SQLiteStorageProvider database = database("admin.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Antonio");
    ToggleStorage storage = new ToggleStorage(database);
    WalletService wallet = wallet(storage, new RecoveryJournal(temp.resolve("admin-journal")), 64, 250);
    wallet.load(player, "Antonio").join();
    storage.available = false;
    EconomyResult first = wallet.administrativeMutation(player, MutationKind.CREDIT, 100,
        TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
    assertThat(first.status()).isEqualTo(EconomyResult.Status.ADMIN_PENDING);
    for (int attempt = 0; attempt < 4; attempt++) {
      EconomyResult result = wallet.administrativeMutation(player, MutationKind.CREDIT, 100,
          TransactionType.ADMIN_GIVE, "ADMIN_GIVE", "admin:CONSOLE").join();
      assertThat(result.status()).isEqualTo(EconomyResult.Status.ADMIN_ALREADY_PENDING);
      assertThat(result.operationId()).isEqualTo(first.operationId());
    }
    assertThat(database.loadAccount(player).orElseThrow().balance()).isZero();
    assertThat(wallet.metrics().recoveryPending()).isOne();
    assertThat(storage.applyCalls).hasValue(1);
    storage.available = true;
    wallet.replayRecovery().join();
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(100);
    wallet.close();
    database.close();
  }

  @Test
  void completedRewardOwnershipSurvivesSessionReplacementAndIsUnique() {
    PendingCompletedRewards pending = new PendingCompletedRewards(4);
    UUID player = UUID.randomUUID();
    CompletedReward reward = CompletedReward.create(player, 10, Instant.now(), UUID.randomUUID(), 1);
    try (PendingCompletedRewards.Reservation reservation = pending.reserveAvailable()) {
      reservation.commit(List.of(reward));
    }
    assertThat(pending.size()).isOne();
    assertThat(pending.claim(1)).containsExactly(reward);
    pending.retry(reward.operationId());
    assertThat(pending.claim(1)).containsExactly(reward);
    pending.durable(reward.operationId());
    assertThat(pending.size()).isZero();
  }

  @Test
  void zeroPriceCannotEnterPurchasePipeline() {
    assertThat(PurchasePolicy.validate(100, 0, true))
        .isEqualTo(PurchasePolicy.Decision.INVALID_PRICE);
  }

  @Test
  void concurrentAccountSequencesArePositiveAndUnique() throws Exception {
    SQLiteStorageProvider database = database("sequences.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Sequences");
    WalletService wallet = wallet(database,
        new RecoveryJournal(temp.resolve("sequences-journal")), 128, 250);
    wallet.load(player, "Sequences").join();
    List<CompletableFuture<EconomyResult>> mutations = new ArrayList<>();
    for (int index = 0; index < 50; index++) {
      mutations.add(wallet.credit(player, 1, TransactionType.PLAYTIME_REWARD, "sequence", "test"));
    }
    CompletableFuture.allOf(mutations.toArray(CompletableFuture[]::new)).join();
    List<Long> sequences = database.history(player, 0, 50).stream()
        .map(GemTransaction::accountSequence).toList();
    assertThat(sequences).allMatch(value -> value > 0);
    assertThat(sequences).doesNotHaveDuplicates();
    wallet.close();
    database.close();
  }

  @Test
  void versionOneJournalRemainsReadableAfterUpgrade() throws Exception {
    Path directory = temp.resolve("legacy-v1");
    Files.createDirectories(directory);
    EconomyOperation operation = credit(UUID.randomUUID(), 7, 10);
    Path file = directory.resolve(operation.operationId() + ".op");
    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
        new FileOutputStream(file.toFile())))) {
      output.writeInt(0x4E_43_4F_50);
      output.writeInt(1);
      output.writeUTF(operation.operationId().toString());
      output.writeUTF(operation.accountId().toString());
      output.writeUTF(operation.kind().name());
      output.writeLong(operation.amount());
      output.writeUTF(operation.type().name());
      output.writeUTF(operation.reason());
      output.writeUTF(operation.reference());
      output.writeUTF(operation.initialStatus().name());
      output.writeLong(operation.createdAt().toEpochMilli());
    }
    RecoveryJournal upgraded = new RecoveryJournal(directory);
    upgraded.initialize();
    assertThat(upgraded.loadAll()).singleElement().satisfies(loaded -> {
      assertThat(loaded.operationId()).isEqualTo(operation.operationId());
      assertThat(loaded.accountSequence()).isPositive();
    });
  }

  @Test
  void oneCorruptRecordDoesNotHideNineHundredNinetyNineValidRecords() throws Exception {
    Path directory = temp.resolve("corrupt-1000");
    Files.createDirectories(directory);
    UUID player = UUID.randomUUID();
    for (int index = 1; index <= 999; index++) {
      EconomyOperation operation = credit(player, index, 1);
      writeVersionOne(directory.resolve(operation.operationId() + ".op"), operation);
    }
    Files.write(directory.resolve("broken.op"), new byte[] {9, 8, 7});
    RecoveryJournal journal = new RecoveryJournal(directory);
    journal.initialize();
    assertThat(journal.loadBatch(1_000)).hasSize(999);
    assertThat(journal.corruptCount()).isOne();
    assertThat(Files.list(directory.resolve("corrupt"))).hasSize(1);
  }

  @Test
  void manualDeliveredAndRefundAreAuditedAndRefundIsIdempotent() throws Exception {
    Path databasePath = temp.resolve("manual.db");
    SQLiteStorageProvider database = new SQLiteStorageProvider(databasePath);
    database.initialize();
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Manual");
    database.applyOperation(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 100, 1));
    EconomyOperation delivered = purchase(player, UUID.randomUUID(), 25, 2);
    database.applyOperation(delivered);
    database.markDelivery(delivered.operationId(), TransactionStatus.DELIVERY_STARTED, "start");
    database.markDelivery(delivered.operationId(), TransactionStatus.MANUAL_REVIEW, "ambiguous");
    EconomyOperation refunded = purchase(player, UUID.randomUUID(), 25, 3);
    database.applyOperation(refunded);
    database.markDelivery(refunded.operationId(), TransactionStatus.DELIVERY_STARTED, "start");
    database.markDelivery(refunded.operationId(), TransactionStatus.MANUAL_REVIEW, "ambiguous");
    WalletService wallet = wallet(database,
        new RecoveryJournal(temp.resolve("manual-journal")), 64, 250);
    wallet.load(player, "Manual").join();
    wallet.resolveReviewDelivered(delivered.operationId(), "CONSOLE").join();
    assertThat(wallet.resolveReviewRefund(refunded.operationId(), "CONSOLE").join().success()).isTrue();
    assertThat(database.findTransaction(delivered.operationId()).orElseThrow().status())
        .isEqualTo(TransactionStatus.DELIVERED);
    assertThat(database.findTransaction(refunded.operationId()).orElseThrow().status())
        .isEqualTo(TransactionStatus.REFUNDED);
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(75);
    assertThat(wallet.resolveReviewRefund(refunded.operationId(), "CONSOLE").join().success()).isTrue();
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(75);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT COUNT(*) FROM novagems_admin_audit")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt(1)).isEqualTo(2);
    }
    wallet.close();
    database.close();
  }

  @Test
  void confirmedManualRetryPreservesOriginalOperationIdentity() throws Exception {
    SQLiteStorageProvider database = database("retry-review.db");
    UUID player = UUID.randomUUID();
    database.loadOrCreate(player, "Retry");
    database.applyOperation(operation(UUID.randomUUID(), player, MutationKind.CREDIT, 100, 1));
    EconomyOperation purchase = purchase(player, UUID.randomUUID(), 20, 2);
    database.applyOperation(purchase);
    database.markDelivery(purchase.operationId(), TransactionStatus.DELIVERY_STARTED, "start");
    database.markDelivery(purchase.operationId(), TransactionStatus.MANUAL_REVIEW, "ambiguous");
    WalletService wallet = wallet(database,
        new RecoveryJournal(temp.resolve("retry-review-journal")), 64, 250);
    wallet.load(player, "Retry").join();
    EconomyResult retry = wallet.resolveReviewRetry(purchase.operationId(), "CONSOLE").join();
    assertThat(retry.operationId()).isEqualTo(purchase.operationId());
    assertThat(retry.success()).isTrue();
    assertThat(database.findTransaction(purchase.operationId()).orElseThrow().status())
        .isEqualTo(TransactionStatus.DELIVERY_PENDING);
    assertThat(wallet.hasPendingPurchase(player)).isTrue();
    assertThat(database.loadAccount(player).orElseThrow().balance()).isEqualTo(80);
    wallet.close();
    database.close();
  }

  private WalletService wallet(StorageProvider storage, RecoveryJournal journal, int queue, int batch) {
    return new WalletService(storage, temp.resolve("unused"), ignored -> {}, 1024, 64, 4096,
        1, 2, queue, batch, 30, 300, journal);
  }

  private SQLiteStorageProvider database(String name) throws Exception {
    SQLiteStorageProvider database = new SQLiteStorageProvider(temp.resolve(name));
    database.initialize();
    return database;
  }

  private EconomyOperation credit(UUID player, long sequence, long amount) {
    return operation(UUID.randomUUID(), player, MutationKind.CREDIT, amount, sequence);
  }

  private EconomyOperation operation(UUID id, UUID player, MutationKind kind, long amount, long sequence) {
    return new EconomyOperation(id, player, kind, amount, TransactionType.PLAYTIME_REWARD,
        "test", "test", TransactionStatus.COMMITTED, Instant.now(), sequence);
  }

  private EconomyOperation purchase(UUID player, UUID id, long amount, long sequence) {
    return new EconomyOperation(id, player, MutationKind.DEBIT, amount,
        TransactionType.SHOP_PURCHASE, "SHOP_PURCHASE", "command-reward",
        TransactionStatus.DELIVERY_PENDING, Instant.now(), sequence);
  }

  private void writeVersionOne(Path file, EconomyOperation operation) throws Exception {
    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
        new FileOutputStream(file.toFile())))) {
      output.writeInt(0x4E_43_4F_50);
      output.writeInt(1);
      output.writeUTF(operation.operationId().toString());
      output.writeUTF(operation.accountId().toString());
      output.writeUTF(operation.kind().name());
      output.writeLong(operation.amount());
      output.writeUTF(operation.type().name());
      output.writeUTF(operation.reason());
      output.writeUTF(operation.reference());
      output.writeUTF(operation.initialStatus().name());
      output.writeLong(operation.createdAt().toEpochMilli());
    }
  }

  private static final class MemoryJournal extends RecoveryJournal {
    private final List<EconomyOperation> operations = new ArrayList<>();
    private MemoryJournal() { super(Path.of("memory-only")); }
    @Override public synchronized void initialize() {}
    @Override public synchronized void store(EconomyOperation operation) {
      if (operations.stream().noneMatch(value -> value.operationId().equals(operation.operationId())))
        operations.add(operation);
    }
    @Override public synchronized void remove(UUID operationId) {
      operations.removeIf(value -> value.operationId().equals(operationId));
    }
    @Override public synchronized List<EconomyOperation> loadBatch(int limit) {
      return operations.stream().sorted(Comparator.comparing(EconomyOperation::accountId)
          .thenComparingLong(EconomyOperation::accountSequence)).limit(limit).toList();
    }
    @Override public synchronized int pendingCount() { return operations.size(); }
    @Override public synchronized int corruptCount() { return 0; }
  }

  private static class CountingUnavailableStorage implements StorageProvider {
    final AtomicInteger applyCalls = new AtomicInteger();
    public void initialize() {}
    public Optional<PlayerAccount> loadAccount(UUID uuid) { return Optional.empty(); }
    public Optional<UUID> findUuidByName(String name) { return Optional.empty(); }
    public PlayerAccount loadOrCreate(UUID uuid, String name) { return PlayerAccount.newAccount(uuid, name); }
    public DurableMutationResult applyOperation(EconomyOperation operation) {
      applyCalls.incrementAndGet();
      throw new IllegalStateException("database unavailable");
    }
    public void markDelivery(UUID id, TransactionStatus status, String error) {}
    public Optional<GemTransaction> findTransaction(UUID id) { return Optional.empty(); }
    public List<LeaderboardEntry> leaderboard(int offset, int limit) { return List.of(); }
    public List<GemTransaction> history(UUID uuid, int offset, int limit) { return List.of(); }
    public List<GemTransaction> deliveryFailures(int offset, int limit) { return List.of(); }
    public long countTransactions(TransactionStatus status) { return 0; }
    public int workerThreads() { return 1; }
    public String description() { return "unavailable"; }
    public void close() {}
  }

  private static final class ToggleStorage implements StorageProvider {
    private final StorageProvider delegate;
    private volatile boolean available = true;
    private final AtomicInteger applyCalls = new AtomicInteger();
    private ToggleStorage(StorageProvider delegate) { this.delegate = delegate; }
    private void check() { if (!available) throw new IllegalStateException("storage unavailable"); }
    public void initialize() throws Exception { delegate.initialize(); }
    public Optional<PlayerAccount> loadAccount(UUID id) throws Exception { check(); return delegate.loadAccount(id); }
    public Optional<UUID> findUuidByName(String name) throws Exception { check(); return delegate.findUuidByName(name); }
    public PlayerAccount loadOrCreate(UUID id, String name) throws Exception { check(); return delegate.loadOrCreate(id, name); }
    public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception {
      applyCalls.incrementAndGet(); check(); return delegate.applyOperation(operation);
    }
    public void markDelivery(UUID id, TransactionStatus status, String error) throws Exception { check(); delegate.markDelivery(id, status, error); }
    public Optional<GemTransaction> findTransaction(UUID id) throws Exception { check(); return delegate.findTransaction(id); }
    public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception { check(); return delegate.leaderboard(offset, limit); }
    public List<GemTransaction> history(UUID id, int offset, int limit) throws Exception { check(); return delegate.history(id, offset, limit); }
    public List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception { check(); return delegate.deliveryFailures(offset, limit); }
    public long countTransactions(TransactionStatus status) throws Exception { check(); return delegate.countTransactions(status); }
    public int workerThreads() { return 1; }
    public String description() { return "toggle"; }
    public void close() {}
  }
}
