package net.watones.novagems.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.PlayerAccount;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteStorageProviderTest {
  @Test
  void version112SchemaAndDataRemainIntactOn114Startup(@TempDir Path temp) throws Exception {
    Path database = temp.resolve("v112.db");
    UUID player = UUID.randomUUID();
    UUID seedId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    SQLiteStorageProvider version112 = new SQLiteStorageProvider(database);
    version112.initialize();
    version112.loadOrCreate(player, "Antonio");
    version112.applyOperation(new EconomyOperation(seedId, player, MutationKind.CREDIT, 100,
        TransactionType.ADMIN_GIVE, "seed", "admin:CONSOLE", TransactionStatus.COMMITTED,
        Instant.now(), 1));
    version112.applyOperation(new EconomyOperation(deliveryId, player, MutationKind.DEBIT, 25,
        TransactionType.SHOP_PURCHASE, "SHOP_PURCHASE", "command", 
        TransactionStatus.DELIVERY_PENDING, Instant.now(), 2));
    version112.markDelivery(deliveryId, TransactionStatus.DELIVERY_STARTED, null);
    version112.markDelivery(deliveryId, TransactionStatus.MANUAL_REVIEW, "ambiguous");
    version112.resolveManualReview(deliveryId, TransactionStatus.DELIVERED, "CONSOLE",
        "MIGRATION_FIXTURE", "v1.1.2 audit");
    version112.close();

    SQLiteStorageProvider version113 = new SQLiteStorageProvider(database);
    version113.initialize();
    assertThat(version113.loadAccount(player).orElseThrow().balance()).isEqualTo(75);
    assertThat(version113.findTransaction(seedId)).isPresent();
    assertThat(version113.findTransaction(deliveryId)).hasValueSatisfying(transaction ->
        assertThat(transaction.status()).isEqualTo(TransactionStatus.DELIVERED));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Statement statement = connection.createStatement()) {
      try (ResultSet version = statement.executeQuery(
          "SELECT schema_version FROM novagems_schema WHERE schema_key='core'")) {
        assertThat(version.next()).isTrue();
        assertThat(version.getInt(1)).isEqualTo(5);
      }
      try (ResultSet audit = statement.executeQuery(
          "SELECT COUNT(*) FROM novagems_admin_audit WHERE operation_id='" + deliveryId + "'")) {
        assertThat(audit.next()).isTrue();
        assertThat(audit.getInt(1)).isEqualTo(1);
      }
    }
    version113.close();
  }

  @Test
  void versionTwoMigrationPreservesEconomicDataAndCreatesVersionFiveAudit(@TempDir Path temp)
      throws Exception {
    Path database = temp.resolve("v2.db");
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000321");
    UUID operation = UUID.fromString("00000000-0000-0000-0000-000000000654");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE coin_accounts (uuid VARCHAR(36) PRIMARY KEY,last_name VARCHAR(16) NOT NULL,balance BIGINT NOT NULL,lifetime_earned BIGINT NOT NULL,lifetime_spent BIGINT NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
      statement.executeUpdate(
          "CREATE TABLE coin_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,operation_id VARCHAR(36),uuid VARCHAR(36) NOT NULL,amount BIGINT NOT NULL,balance_before BIGINT NOT NULL,balance_after BIGINT NOT NULL,transaction_type VARCHAR(32) NOT NULL,reason VARCHAR(128) NOT NULL,reference_value VARCHAR(128),status VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',delivery_error VARCHAR(512),created_at BIGINT NOT NULL,completed_at BIGINT)");
      statement.executeUpdate(
          "CREATE TABLE novagems_schema (schema_key VARCHAR(32) PRIMARY KEY,schema_version INTEGER NOT NULL)");
      statement.executeUpdate("INSERT INTO novagems_schema VALUES('core',2)");
      statement.executeUpdate(
          "INSERT INTO coin_accounts VALUES('" + id + "','V2',75,100,25,1,2)");
      statement.executeUpdate(
          "INSERT INTO coin_transactions(operation_id,uuid,amount,balance_before,balance_after,transaction_type,reason,reference_value,status,created_at,completed_at) VALUES('"
              + operation + "','" + id + "',25,100,75,'SHOP_PURCHASE','v2','key','DELIVERED',2,2)");
    }

    SQLiteStorageProvider storage = new SQLiteStorageProvider(database);
    storage.initialize();
    assertThat(storage.loadAccount(id).orElseThrow().snapshot()).satisfies(account -> {
      assertThat(account.balance()).isEqualTo(75);
      assertThat(account.lifetimeEarned()).isEqualTo(100);
      assertThat(account.lifetimeSpent()).isEqualTo(25);
    });
    assertThat(storage.findTransaction(operation)).hasValueSatisfying(
        transaction -> assertThat(transaction.status()).isEqualTo(TransactionStatus.DELIVERED));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Statement statement = connection.createStatement()) {
      try (ResultSet version =
          statement.executeQuery(
              "SELECT schema_version FROM novagems_schema WHERE schema_key='core'")) {
        assertThat(version.next()).isTrue();
        assertThat(version.getInt(1)).isEqualTo(5);
      }
      try (ResultSet audit =
          statement.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='table' AND name='novagems_admin_audit'")) {
        assertThat(audit.next()).isTrue();
      }
    }
    storage.close();
  }

  @Test
  void additiveMigrationPreservesVersionOneRows(@TempDir Path temp) throws Exception {
    Path database = temp.resolve("legacy.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE coin_accounts (uuid VARCHAR(36) PRIMARY KEY,last_name VARCHAR(16) NOT NULL,balance BIGINT NOT NULL,lifetime_earned BIGINT NOT NULL,lifetime_spent BIGINT NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
      statement.executeUpdate(
          "CREATE TABLE coin_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,uuid VARCHAR(36) NOT NULL,amount BIGINT NOT NULL,balance_before BIGINT NOT NULL,balance_after BIGINT NOT NULL,transaction_type VARCHAR(32) NOT NULL,reason VARCHAR(128) NOT NULL,reference_value VARCHAR(128),created_at BIGINT NOT NULL)");
      UUID id = UUID.fromString("00000000-0000-0000-0000-000000000123");
      statement.executeUpdate(
          "INSERT INTO coin_accounts VALUES('"
              + id
              + "','Legacy',42,42,0,1,1)");
      statement.executeUpdate(
          "INSERT INTO coin_transactions(uuid,amount,balance_before,balance_after,transaction_type,reason,reference_value,created_at) VALUES('"
              + id
              + "',42,0,42,'PLAYTIME_REWARD','legacy','v1',1)");
    }
    SQLiteStorageProvider storage = new SQLiteStorageProvider(database);
    storage.initialize();
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000123");
    assertThat(storage.loadAccount(id).orElseThrow().balance()).isEqualTo(42);
    assertThat(storage.history(id, 0, 10)).singleElement().satisfies(
        transaction -> {
          assertThat(transaction.operationId()).isNotNull();
          assertThat(transaction.status()).isEqualTo(TransactionStatus.COMMITTED);
        });
    assertThat(storage.claimRewardNotification(id)).isEmpty();
    storage.close();
  }

  @Test
  void migrationsAreIdempotentAndOperationIdsPreventDoubleApply(@TempDir Path temp)
      throws Exception {
    SQLiteStorageProvider storage = new SQLiteStorageProvider(temp.resolve("test.db"));
    storage.initialize();
    storage.initialize();
    UUID uuid = UUID.randomUUID();
    storage.loadOrCreate(uuid, "Steve");
    EconomyOperation operation =
        new EconomyOperation(
            UUID.randomUUID(),
            uuid,
            MutationKind.CREDIT,
            10,
            TransactionType.PLAYTIME_REWARD,
            "SESSION_INTERVAL",
            "session:live",
            TransactionStatus.COMMITTED,
            Instant.now());

    assertThat(storage.applyOperation(operation).status())
        .isEqualTo(DurableMutationResult.Status.APPLIED);
    assertThat(storage.applyOperation(operation).status())
        .isEqualTo(DurableMutationResult.Status.DUPLICATE);
    assertThat(storage.loadAccount(uuid).orElseThrow().balance()).isEqualTo(10);
    assertThat(storage.history(uuid, 0, 10)).hasSize(1);
    storage.close();
  }

  @Test
  void deliveryStateIsPersistedAndQueryable(@TempDir Path temp) throws Exception {
    SQLiteStorageProvider storage = new SQLiteStorageProvider(temp.resolve("delivery.db"));
    storage.initialize();
    UUID uuid = UUID.randomUUID();
    storage.loadOrCreate(uuid, "Alex");
    storage.applyOperation(
        new EconomyOperation(
            UUID.randomUUID(),
            uuid,
            MutationKind.CREDIT,
            100,
            TransactionType.ADMIN_GIVE,
            "seed",
            "test",
            TransactionStatus.COMMITTED,
            Instant.now()));
    EconomyOperation operation =
        new EconomyOperation(
            UUID.randomUUID(),
            uuid,
            MutationKind.DEBIT,
            25,
            TransactionType.SHOP_PURCHASE,
            "SHOP_PURCHASE",
            "key",
            TransactionStatus.DELIVERY_PENDING,
            Instant.now());
    storage.applyOperation(operation);
    storage.markDelivery(operation.operationId(), TransactionStatus.DELIVERY_FAILED, "known fail");

    assertThat(storage.deliveryFailures(0, 10)).singleElement().satisfies(
        transaction -> {
          assertThat(transaction.status()).isEqualTo(TransactionStatus.DELIVERY_FAILED);
          assertThat(transaction.deliveryError()).isEqualTo("known fail");
          assertThat(transaction.completedAt()).isNotNull();
        });
    storage.close();
  }

  @Test
  void leaderboardHistoryAndBalancesSurviveProviderRestart(@TempDir Path temp) throws Exception {
    Path database = temp.resolve("restart.db");
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    SQLiteStorageProvider first = new SQLiteStorageProvider(database);
    first.initialize();
    first.loadOrCreate(alice, "Alice");
    first.loadOrCreate(bob, "Bob");
    first.applyOperation(credit(alice, 15));
    first.applyOperation(credit(bob, 30));
    first.close();

    SQLiteStorageProvider restarted = new SQLiteStorageProvider(database);
    restarted.initialize();
    assertThat(restarted.loadAccount(alice).orElseThrow().balance()).isEqualTo(15);
    assertThat(restarted.history(bob, 0, 10)).hasSize(1);
    assertThat(restarted.leaderboard(0, 10))
        .extracting(StorageProvider.LeaderboardEntry::name)
        .containsExactly("Bob", "Alice");
    restarted.close();
  }

  private EconomyOperation credit(UUID accountId, long amount) {
    return new EconomyOperation(
        UUID.randomUUID(),
        accountId,
        MutationKind.CREDIT,
        amount,
        TransactionType.ADMIN_GIVE,
        "test",
        "restart",
        TransactionStatus.COMMITTED,
        Instant.now());
  }
}
