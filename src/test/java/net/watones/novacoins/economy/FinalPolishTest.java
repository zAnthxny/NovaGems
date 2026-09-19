package net.watones.novacoins.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.watones.novacoins.storage.RecoveryJournal;
import net.watones.novacoins.storage.SQLiteStorageProvider;
import net.watones.novacoins.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinalPolishTest {
  @TempDir Path temp;

  @Test
  void rejectedCapturesDoNotLeakAssignedSequences() throws Exception {
    SQLiteStorageProvider database = database("rejected.db");
    WalletService wallet = wallet(database, new AlwaysFailStoreJournal(temp.resolve("reject")), 8,
        250);
    List<CompletableFuture<EconomyResult>> results = new ArrayList<>();
    UUID account = UUID.randomUUID();
    for (int index = 0; index < 1_000; index++) {
      results.add(wallet.capture(reward(account, UUID.randomUUID(), 1)).result());
    }
    CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).join();
    assertThat(wallet.assignedSequenceCount()).isZero();
    wallet.close();
    database.close();
  }

  @Test
  void normalRewardNotificationIsClaimedOnlyOnce() throws Exception {
    try (SQLiteStorageProvider database = database("notice-once.db")) {
      UUID account = UUID.randomUUID();
      database.loadOrCreate(account, "Online");
      database.applyOperation(reward(account, UUID.randomUUID(), 10));
      assertThat(database.claimRewardNotification(account)).hasValueSatisfying(notice -> {
        assertThat(notice.rewards()).isOne();
        assertThat(notice.amount()).isEqualTo(10);
        assertThat(notice.balance()).isEqualTo(10);
      });
      assertThat(database.claimRewardNotification(account)).isEmpty();
    }
  }

  @Test
  void recoveredRewardWhileOnlineEmitsOneDurableNotice() throws Exception {
    SQLiteStorageProvider database = database("notice-recovery.db");
    UUID account = UUID.randomUUID();
    database.loadOrCreate(account, "Online");
    Path journalPath = temp.resolve("notice-recovery");
    RecoveryJournal seed = new RecoveryJournal(journalPath);
    seed.initialize();
    seed.store(reward(account, UUID.randomUUID(), 10));
    WalletService wallet = wallet(database, new RecoveryJournal(journalPath), 64, 250);
    CountDownLatch notified = new CountDownLatch(1);
    List<StorageProvider.RewardNotification> notices = new ArrayList<>();
    wallet.onRecoveredRewardReady(id -> wallet.claimRewardNotification(id).thenAccept(claim -> {
      claim.ifPresent(notices::add);
      notified.countDown();
    }));

    WalletService.RecoveryReport report = wallet.replayRecovery().join();
    assertThat(report.actualProgress()).isOne();
    assertThat(notified.await(3, TimeUnit.SECONDS)).isTrue();
    assertThat(notices).singleElement().satisfies(notice -> {
      assertThat(notice.rewards()).isOne();
      assertThat(notice.amount()).isEqualTo(10);
    });
    assertThat(database.claimRewardNotification(account)).isEmpty();
    wallet.close();
    database.close();
  }

  @Test
  void threeRecoveredRewardsOfflineAggregateOnNextLoginWithoutRepeat() throws Exception {
    SQLiteStorageProvider database = database("notice-offline.db");
    UUID account = UUID.randomUUID();
    database.loadOrCreate(account, "Offline");
    Path journalPath = temp.resolve("notice-offline");
    RecoveryJournal seed = new RecoveryJournal(journalPath);
    seed.initialize();
    for (int index = 0; index < 3; index++) {
      seed.store(reward(account, UUID.randomUUID(), 10).withAccountSequence(index + 1));
    }
    WalletService wallet = wallet(database, new RecoveryJournal(journalPath), 64, 250);
    assertThat(wallet.replayRecovery().join().actualProgress()).isEqualTo(3);

    StorageProvider.RewardNotification nextLogin =
        wallet.claimRewardNotification(account).join().orElseThrow();
    assertThat(nextLogin.rewards()).isEqualTo(3);
    assertThat(nextLogin.amount()).isEqualTo(30);
    assertThat(nextLogin.balance()).isEqualTo(30);
    assertThat(wallet.claimRewardNotification(account).join()).isEmpty();
    wallet.close();
    database.close();
  }

  @Test
  void inspectedPendingDeliveriesDoNotScheduleFastRecoveryLoop() throws Exception {
    SQLiteStorageProvider database = database("no-fast-loop.db");
    UUID account = UUID.randomUUID();
    database.loadOrCreate(account, "Delivery");
    database.applyOperation(new EconomyOperation(UUID.randomUUID(), account, MutationKind.CREDIT,
        1_000, TransactionType.ADMIN_GIVE, "seed", "test", TransactionStatus.COMMITTED,
        Instant.now(), 1));
    Path journalPath = temp.resolve("no-fast-loop");
    RecoveryJournal seed = new RecoveryJournal(journalPath);
    seed.initialize();
    for (int index = 0; index < 250; index++) {
      EconomyOperation purchase = new EconomyOperation(UUID.randomUUID(), account,
          MutationKind.DEBIT, 1, TransactionType.SHOP_PURCHASE, "SHOP_PURCHASE", "item",
          TransactionStatus.DELIVERY_PENDING, Instant.now(), index + 2L);
      database.applyOperation(purchase);
      seed.store(purchase);
    }
    WalletService wallet = wallet(database, new RecoveryJournal(journalPath), 64, 250);
    WalletService.RecoveryReport report = wallet.replayRecovery().join();
    assertThat(report.recovered()).isEqualTo(250);
    assertThat(report.actualProgress()).isZero();
    assertThat(report.stillPending()).isEqualTo(250);
    assertThat(wallet.fastRecoveryScheduled()).isFalse();
    wallet.close();
    database.close();
  }

  private SQLiteStorageProvider database(String name) throws Exception {
    SQLiteStorageProvider database = new SQLiteStorageProvider(temp.resolve(name));
    database.initialize();
    return database;
  }

  private WalletService wallet(SQLiteStorageProvider storage, RecoveryJournal journal,
      int journalCapacity, int batchSize) {
    return new WalletService(storage, temp.resolve("unused"), ignored -> {}, 256, 64, 2048,
        1, 2, journalCapacity, batchSize, 30, 300, journal);
  }

  private static EconomyOperation reward(UUID account, UUID operation, long amount) {
    return new EconomyOperation(operation, account, MutationKind.CREDIT, amount,
        TransactionType.PLAYTIME_REWARD, "SESSION_INTERVAL", "test", TransactionStatus.COMMITTED,
        Instant.now());
  }

  private static final class AlwaysFailStoreJournal extends RecoveryJournal {
    AlwaysFailStoreJournal(Path directory) { super(directory); }
    @Override public synchronized void store(EconomyOperation operation) throws IOException {
      throw new IOException("simulated journal failure");
    }
  }
}
