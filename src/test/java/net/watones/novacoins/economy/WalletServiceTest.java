package net.watones.novacoins.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.watones.novacoins.storage.DurableMutationResult;
import net.watones.novacoins.storage.SQLiteStorageProvider;
import net.watones.novacoins.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalletServiceTest {
  @TempDir Path temp;
  private SQLiteStorageProvider storage;
  private WalletService wallets;
  private UUID uuid;

  @BeforeEach
  void setUp() throws Exception {
    storage = new SQLiteStorageProvider(temp.resolve("wallet.db"));
    storage.initialize();
    wallets = new WalletService(storage, temp.resolve("recovery"), error -> {}, 256, 64, 512);
    uuid = UUID.randomUUID();
    wallets.load(uuid, "Alex").join();
  }

  @AfterEach
  void close() throws Exception {
    wallets.close();
    storage.close();
  }

  @Test
  void giveTakeAndSetCommitDurably() throws Exception {
    assertThat(
            wallets
                .credit(uuid, 100, TransactionType.ADMIN_GIVE, "test", "test")
                .join()
                .balanceAfter())
        .isEqualTo(100);
    assertThat(
            wallets
                .debit(uuid, 40, TransactionType.ADMIN_TAKE, "test", "test")
                .join()
                .balanceAfter())
        .isEqualTo(60);
    assertThat(
            wallets
                .set(uuid, 7, TransactionType.ADMIN_SET, "test", "test")
                .join()
                .balanceAfter())
        .isEqualTo(7);
    assertThat(storage.loadAccount(uuid).orElseThrow().balance()).isEqualTo(7);
  }

  @Test
  void insufficientFundsNeverMakesNegative() throws Exception {
    EconomyResult result =
        wallets
            .debit(uuid, 1, TransactionType.SHOP_PURCHASE, "test", "test")
            .join();
    assertThat(result.status()).isEqualTo(EconomyResult.Status.INSUFFICIENT_FUNDS);
    assertThat(wallets.account(uuid).orElseThrow().balance()).isZero();
    assertThat(storage.history(uuid, 0, 10)).isEmpty();
  }

  @Test
  void rejectsNegativeAndDetectsOverflow() {
    assertThat(
            wallets
                .credit(uuid, -1, TransactionType.ADMIN_GIVE, "test", "test")
                .join()
                .status())
        .isEqualTo(EconomyResult.Status.INVALID_AMOUNT);
    wallets.set(uuid, Long.MAX_VALUE, TransactionType.ADMIN_SET, "test", "test").join();
    assertThat(
            wallets
                .credit(uuid, 1, TransactionType.ADMIN_GIVE, "test", "test")
                .join()
                .status())
        .isEqualTo(EconomyResult.Status.OVERFLOW);
    assertThat(wallets.account(uuid).orElseThrow().balance()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void cacheDoesNotChangeBeforeStorageCommit() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    BlockingStorage blocking = new BlockingStorage(storage, entered, release);
    WalletService isolated =
        new WalletService(blocking, temp.resolve("blocking-recovery"), error -> {}, 32, 8, 32);
    UUID accountId = UUID.randomUUID();
    isolated.load(accountId, "CommitOrder").join();

    CompletableFuture<EconomyResult> mutation =
        isolated.credit(accountId, 25, TransactionType.ADMIN_GIVE, "test", "test");
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(isolated.account(accountId).orElseThrow().balance()).isZero();
    release.countDown();
    assertThat(mutation.join().durable()).isTrue();
    assertThat(isolated.account(accountId).orElseThrow().balance()).isEqualTo(25);
    isolated.close();
  }

  @Test
  void concurrentRequestsRemainSerialPerAccount() throws Exception {
    List<CompletableFuture<EconomyResult>> operations = new ArrayList<>();
    for (int index = 0; index < 50; index++) {
      operations.add(
          wallets.credit(uuid, 1, TransactionType.ADMIN_GIVE, "parallel", "test"));
    }
    CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).join();
    assertThat(operations).allMatch(operation -> operation.join().success());
    assertThat(wallets.account(uuid).orElseThrow().balance()).isEqualTo(50);
    assertThat(storage.loadAccount(uuid).orElseThrow().balance()).isEqualTo(50);
  }

  @Test
  void databaseFailureIsNotAcknowledgedAndLeavesRecoveryRecord() {
    BlockingStorage failing =
        new BlockingStorage(storage, new CountDownLatch(0), new CountDownLatch(0));
    failing.failing = true;
    AtomicReference<Throwable> logged = new AtomicReference<>();
    WalletService isolated =
        new WalletService(failing, temp.resolve("failure-recovery"), logged::set, 32, 8, 32);
    UUID accountId = UUID.randomUUID();
    isolated.load(accountId, "Failure").join();

    EconomyResult result =
        isolated
            .credit(accountId, 10, TransactionType.ADMIN_GIVE, "failure", "test")
            .join();
    assertThat(result.status()).isEqualTo(EconomyResult.Status.RECOVERY_PENDING);
    assertThat(result.success()).isFalse();
    assertThat(isolated.account(accountId).orElseThrow().balance()).isZero();
    assertThat(temp.resolve("failure-recovery").toFile().listFiles((dir, name) -> name.endsWith(".op")))
        .hasSize(1);
    assertThat(logged).hasValueSatisfying(error -> assertThat(error).hasMessage("simulated DB failure"));
    isolated.close();
  }

  @Test
  void differentAccountsCanBeSubmittedIndependently() {
    UUID second = UUID.randomUUID();
    wallets.load(second, "Sam").join();
    CompletableFuture<EconomyResult> first =
        wallets.credit(uuid, 3, TransactionType.ADMIN_GIVE, "parallel", "a");
    CompletableFuture<EconomyResult> other =
        wallets.credit(second, 4, TransactionType.ADMIN_GIVE, "parallel", "b");
    CompletableFuture.allOf(first, other).join();
    assertThat(first.join().balanceAfter()).isEqualTo(3);
    assertThat(other.join().balanceAfter()).isEqualTo(4);
  }

  @Test
  void leaderboardCacheIsStrictlyBounded() {
    for (int page = 1; page <= 160; page++) wallets.leaderboard(page, 10).join();
    assertThat(wallets.leaderboardCacheSize()).isLessThanOrEqualTo(128);
  }

  private static final class BlockingStorage implements StorageProvider {
    private final StorageProvider delegate;
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private volatile boolean failing;

    BlockingStorage(StorageProvider delegate, CountDownLatch entered, CountDownLatch release) {
      this.delegate = delegate;
      this.entered = entered;
      this.release = release;
    }

    public void initialize() throws Exception {
      delegate.initialize();
    }

    public Optional<PlayerAccount> loadAccount(UUID id) throws Exception {
      return delegate.loadAccount(id);
    }

    public Optional<UUID> findUuidByName(String name) throws Exception {
      return delegate.findUuidByName(name);
    }

    public PlayerAccount loadOrCreate(UUID id, String name) throws Exception {
      return delegate.loadOrCreate(id, name);
    }

    public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
      if (failing) throw new IllegalStateException("simulated DB failure");
      return delegate.applyOperation(operation);
    }

    public void markDelivery(UUID operationId, TransactionStatus status, String error)
        throws Exception {
      delegate.markDelivery(operationId, status, error);
    }

    public Optional<CoinTransaction> findTransaction(UUID operationId) throws Exception {
      return delegate.findTransaction(operationId);
    }

    public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception {
      return delegate.leaderboard(offset, limit);
    }

    public List<CoinTransaction> history(UUID id, int offset, int limit) throws Exception {
      return delegate.history(id, offset, limit);
    }

    public List<CoinTransaction> deliveryFailures(int offset, int limit) throws Exception {
      return delegate.deliveryFailures(offset, limit);
    }

    public long countTransactions(TransactionStatus status) throws Exception {
      return delegate.countTransactions(status);
    }

    public int workerThreads() {
      return 1;
    }

    public String description() {
      return "blocking";
    }

    public void close() {}
  }
}
