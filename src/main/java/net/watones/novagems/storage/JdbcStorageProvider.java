package net.watones.novagems.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.watones.novagems.economy.GemTransaction;
import net.watones.novagems.economy.DeliveryStateMachine;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.PlayerAccount;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;

/** Shared, additive JDBC schema and the authoritative balance mutation transaction. */
public abstract class JdbcStorageProvider implements StorageProvider {
  private static final int SCHEMA_VERSION = 5;
  private volatile long maxBalance = Long.MAX_VALUE;
  private volatile boolean maxBalanceConfigured = false;

  @Override
  public void configureMaxBalance(long maxBalance) {
    if (maxBalance < 1) throw new IllegalArgumentException("maxBalance must be positive");
    this.maxBalance = maxBalance;
    this.maxBalanceConfigured = true;
  }

  protected abstract DataSource dataSource();

  protected abstract String idColumn();

  protected boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public void initialize() throws Exception {
    try (Connection connection = dataSource().getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS coin_accounts (uuid VARCHAR(36) PRIMARY KEY, last_name"
              + " VARCHAR(16) NOT NULL, balance BIGINT NOT NULL, lifetime_earned BIGINT NOT NULL,"
              + " lifetime_spent BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT"
              + " NULL)");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS coin_transactions (id "
              + idColumn()
              + ", operation_id VARCHAR(36), uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,"
              + " balance_before BIGINT NOT NULL, balance_after BIGINT NOT NULL, transaction_type"
              + " VARCHAR(32) NOT NULL, reason VARCHAR(128) NOT NULL, reference_value VARCHAR(128),"
              + " status VARCHAR(32) NOT NULL DEFAULT 'COMMITTED', delivery_error VARCHAR(512),"
              + " created_at BIGINT NOT NULL, completed_at BIGINT, account_sequence BIGINT NOT NULL DEFAULT 0,"
              + " reward_notified INTEGER NOT NULL DEFAULT 0, reward_notification_claim VARCHAR(36))");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS novacoins_schema (schema_key VARCHAR(32) PRIMARY KEY,"
              + " schema_version INTEGER NOT NULL)");
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS novacoins_admin_audit (id "
              + idColumn()
              + ", admin_uuid VARCHAR(36) NOT NULL, action VARCHAR(32) NOT NULL, operation_id"
              + " VARCHAR(36) NOT NULL, old_status VARCHAR(32) NOT NULL, new_status VARCHAR(32)"
              + " NOT NULL, details VARCHAR(512), created_at BIGINT NOT NULL)");
    }

    migrateTransactionColumns();
    backfillOperationIds();
    ensureIndex("coin_transactions", "uq_coin_transactions_operation", "operation_id", true);
    ensureIndex("coin_transactions", "idx_coin_transactions_uuid_created", "uuid, created_at", false);
    ensureIndex("coin_transactions", "idx_coin_transactions_type_created", "transaction_type, created_at", false);
    ensureIndex("coin_transactions", "idx_coin_transactions_status_created", "status, created_at", false);
    ensureIndex("coin_accounts", "idx_coin_accounts_balance", "balance", false);
    ensureIndex(
        "novacoins_admin_audit", "idx_novacoins_audit_operation", "operation_id, created_at", false);
    writeSchemaVersion();
  }

  private void migrateTransactionColumns() throws SQLException {
    addColumnIfMissing("coin_transactions", "operation_id", "VARCHAR(36)");
    addColumnIfMissing(
        "coin_transactions", "status", "VARCHAR(32) NOT NULL DEFAULT 'COMMITTED'");
    addColumnIfMissing("coin_transactions", "delivery_error", "VARCHAR(512)");
    addColumnIfMissing("coin_transactions", "completed_at", "BIGINT");
    addColumnIfMissing(
        "coin_transactions", "account_sequence", "BIGINT NOT NULL DEFAULT 0");
    boolean notificationColumnAdded = addColumnIfMissing(
        "coin_transactions", "reward_notified", "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing("coin_transactions", "reward_notification_claim", "VARCHAR(36)");
    // Existing rewards predate this feature and must not produce a notification storm on upgrade.
    if (notificationColumnAdded) {
      try (Connection connection = dataSource().getConnection();
          Statement statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE coin_transactions SET reward_notified=1");
      }
    }
    addColumnIfMissing("novacoins_admin_audit", "details", "VARCHAR(512)");
  }

  private boolean addColumnIfMissing(String table, String column, String definition)
      throws SQLException {
    try (Connection connection = dataSource().getConnection()) {
      if (hasColumn(connection.getMetaData(), table, column)) return false;
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
      }
      return true;
    }
  }

  private boolean hasColumn(DatabaseMetaData metadata, String table, String column)
      throws SQLException {
    for (String candidate : List.of(table, table.toUpperCase(), table.toLowerCase())) {
      try (ResultSet result = metadata.getColumns(null, null, candidate, null)) {
        while (result.next()) {
          if (column.equalsIgnoreCase(result.getString("COLUMN_NAME"))) return true;
        }
      }
    }
    return false;
  }

  private void backfillOperationIds() throws SQLException {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT id FROM coin_transactions WHERE operation_id IS NULL OR operation_id=''");
        ResultSet result = select.executeQuery();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE coin_transactions SET operation_id=? WHERE id=? AND (operation_id IS NULL OR operation_id='')")) {
      while (result.next()) {
        update.setString(1, UUID.randomUUID().toString());
        update.setLong(2, result.getLong(1));
        update.addBatch();
      }
      update.executeBatch();
    }
  }

  private void writeSchemaVersion() throws SQLException {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE novacoins_schema SET schema_version=? WHERE schema_key='core'")) {
      update.setInt(1, SCHEMA_VERSION);
      if (update.executeUpdate() != 0) return;
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO novacoins_schema(schema_key,schema_version) VALUES('core',?)")) {
        insert.setInt(1, SCHEMA_VERSION);
        insert.executeUpdate();
      } catch (SQLException race) {
        if (!isConstraintViolation(race)) throw race;
      }
    }
  }

  private void ensureIndex(String table, String index, String columns, boolean unique)
      throws SQLException {
    try (Connection connection = dataSource().getConnection()) {
      boolean exists = false;
      for (String candidate : List.of(table, table.toUpperCase(), table.toLowerCase())) {
        try (ResultSet result =
            connection.getMetaData().getIndexInfo(null, null, candidate, false, false)) {
          while (result.next()) {
            if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
              exists = true;
              break;
            }
          }
        }
        if (exists) break;
      }
      if (!exists) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate(
              "CREATE "
                  + (unique ? "UNIQUE " : "")
                  + "INDEX "
                  + index
                  + " ON "
                  + table
                  + "("
                  + columns
                  + ")");
        }
      }
    }
  }

  @Override
  public Optional<PlayerAccount> loadAccount(UUID uuid) throws Exception {
    try (Connection connection = dataSource().getConnection()) {
      return loadAccount(connection, uuid, false);
    }
  }

  private Optional<PlayerAccount> loadAccount(Connection connection, UUID uuid, boolean lock)
      throws SQLException {
    String sql =
        "SELECT * FROM coin_accounts WHERE uuid=?"
            + (lock && supportsSelectForUpdate() ? " FOR UPDATE" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(account(result)) : Optional.empty();
      }
    }
  }

  @Override
  public Optional<UUID> findUuidByName(String name) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT uuid FROM coin_accounts WHERE LOWER(last_name)=LOWER(?)")) {
      statement.setString(1, name);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
      }
    }
  }

  @Override
  public PlayerAccount loadOrCreate(UUID uuid, String name) throws Exception {
    try (Connection connection = dataSource().getConnection()) {
      Optional<PlayerAccount> existing = loadAccount(connection, uuid, false);
      if (existing.isPresent()) {
        PlayerAccount account = existing.get();
        if (!account.lastName().equals(name)) {
          Instant now = Instant.now();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE coin_accounts SET last_name=?,updated_at=? WHERE uuid=?")) {
            statement.setString(1, truncate(name, 16));
            statement.setLong(2, now.toEpochMilli());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
          }
          PlayerAccount.Snapshot snapshot = account.snapshot();
          return new PlayerAccount(
              new PlayerAccount.Snapshot(
                  uuid,
                  name,
                  snapshot.balance(),
                  snapshot.lifetimeEarned(),
                  snapshot.lifetimeSpent(),
                  snapshot.createdAt(),
                  now));
        }
        return account;
      }
      PlayerAccount created = PlayerAccount.newAccount(uuid, name);
      insertAccountIfAbsent(connection, created.snapshot());
      return loadAccount(connection, uuid, false).orElseThrow();
    }
  }

  @Override
  public DurableMutationResult applyOperation(EconomyOperation operation) throws Exception {
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<GemTransaction> duplicate = findTransaction(connection, operation.operationId());
        if (duplicate.isPresent()) {
          PlayerAccount.Snapshot snapshot =
              loadAccount(connection, operation.accountId(), false)
                  .orElseThrow(() -> new SQLException("Committed operation has no account"))
                  .snapshot();
          connection.commit();
          return new DurableMutationResult(
              DurableMutationResult.Status.DUPLICATE, snapshot, duplicate.get());
        }

        Optional<PlayerAccount> loaded = loadAccount(connection, operation.accountId(), true);
        if (loaded.isEmpty()
            && (operation.kind() == MutationKind.CREDIT
                || operation.kind() == MutationKind.REFUND_DEBIT)) {
          insertAccountIfAbsent(
              connection,
              PlayerAccount.newAccount(
                      operation.accountId(),
                      "NC-" + operation.accountId().toString().substring(0, 8))
                  .snapshot());
          loaded = loadAccount(connection, operation.accountId(), true);
        }
        if (loaded.isEmpty()) {
          connection.rollback();
          return new DurableMutationResult(
              DurableMutationResult.Status.ACCOUNT_MISSING, null, null);
        }

        PlayerAccount.Snapshot before = loaded.get().snapshot();
        MutationCalculation calculation = calculate(before, operation);
        if (calculation.failure() != null) {
          connection.rollback();
          return new DurableMutationResult(calculation.failure(), before, null);
        }

        PlayerAccount.Snapshot after = calculation.snapshot();
        updateExistingAccount(connection, after);
        GemTransaction transaction = transaction(operation, before.balance(), after.balance());
        insertTransaction(connection, transaction);
        connection.commit();
        return new DurableMutationResult(
            DurableMutationResult.Status.APPLIED, after, transaction);
      } catch (SQLException exception) {
        connection.rollback();
        if (isConstraintViolation(exception)) {
          Optional<GemTransaction> duplicate = findTransaction(operation.operationId());
          if (duplicate.isPresent()) {
            PlayerAccount.Snapshot snapshot =
                loadAccount(operation.accountId())
                    .orElseThrow(() -> new SQLException("Committed operation has no account"))
                    .snapshot();
            return new DurableMutationResult(
                DurableMutationResult.Status.DUPLICATE, snapshot, duplicate.get());
          }
        }
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private MutationCalculation calculate(
      PlayerAccount.Snapshot before, EconomyOperation operation) {
    long amount = operation.amount();
    if (amount < 0 || (operation.kind() != MutationKind.SET && amount == 0)) {
      return MutationCalculation.failed(DurableMutationResult.Status.INVALID_AMOUNT);
    }
    try {
      long balance = before.balance();
      long earned = before.lifetimeEarned();
      long spent = before.lifetimeSpent();
      switch (operation.kind()) {
        case CREDIT -> {
          long applied = maxBalanceConfigured
              ? Math.min(amount, Math.max(0, maxBalance - balance))
              : amount;
          balance = Math.addExact(balance, applied);
          earned = Math.addExact(earned, applied);
        }
        case DEBIT -> {
          if (balance < amount) {
            return MutationCalculation.failed(
                DurableMutationResult.Status.INSUFFICIENT_FUNDS);
          }
          balance -= amount;
          spent = Math.addExact(spent, amount);
        }
        case SET -> {
          if (amount > balance) earned = Math.addExact(earned, amount - balance);
          if (amount < balance) spent = Math.addExact(spent, balance - amount);
          balance = amount;
        }
        case REFUND_DEBIT -> {
          balance = Math.addExact(balance, amount);
          spent = Math.max(0, spent - amount);
        }
      }
      Instant now = Instant.now();
      return MutationCalculation.succeeded(
          new PlayerAccount.Snapshot(
              before.uuid(),
              before.lastName(),
              balance,
              earned,
              spent,
              before.createdAt(),
              now));
    } catch (ArithmeticException overflow) {
      return MutationCalculation.failed(DurableMutationResult.Status.OVERFLOW);
    }
  }

  private GemTransaction transaction(EconomyOperation operation, long before, long after) {
    Instant completed =
        operation.initialStatus() == TransactionStatus.DELIVERY_PENDING
            ? null
            : operation.createdAt();
    return new GemTransaction(
        0,
        operation.operationId(),
        operation.accountId(),
        operation.amount(),
        before,
        after,
        operation.type(),
        operation.reason(),
        operation.reference(),
        operation.initialStatus(),
        null,
        operation.createdAt(),
        completed,
        operation.accountSequence());
  }

  @Override
  public void markDelivery(UUID operationId, TransactionStatus status, String error)
      throws Exception {
    if (status == TransactionStatus.COMMITTED || status == TransactionStatus.DELIVERY_PENDING) {
      throw new IllegalArgumentException("Delivery transition cannot reset the transaction");
    }
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        GemTransaction current =
            findTransaction(connection, operationId)
                .orElseThrow(() -> new SQLException("Unknown operation " + operationId));
        if (!DeliveryStateMachine.canTransition(current.status(), status)) {
          throw new SQLException(
              "Unsafe delivery transition " + current.status() + " -> " + status);
        }
        if (current.status() == status) {
          connection.commit();
          return;
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE coin_transactions SET status=?, delivery_error=?, completed_at=?"
                    + " WHERE operation_id=? AND status=?")) {
          statement.setString(1, status.name());
          if (error == null || error.isBlank()) statement.setNull(2, Types.VARCHAR);
          else statement.setString(2, truncate(error, 512));
          if (status == TransactionStatus.DELIVERY_STARTED) statement.setNull(3, Types.BIGINT);
          else statement.setLong(3, System.currentTimeMillis());
          statement.setString(4, operationId.toString());
          statement.setString(5, current.status().name());
          if (statement.executeUpdate() != 1) {
            throw new SQLException("Concurrent delivery transition for " + operationId);
          }
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  @Override
  public Optional<GemTransaction> findTransaction(UUID operationId) throws Exception {
    try (Connection connection = dataSource().getConnection()) {
      return findTransaction(connection, operationId);
    }
  }

  private Optional<GemTransaction> findTransaction(Connection connection, UUID operationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT * FROM coin_transactions WHERE operation_id=?")) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(transaction(result)) : Optional.empty();
      }
    }
  }

  private void insertAccountIfAbsent(Connection connection, PlayerAccount.Snapshot account)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO coin_accounts(uuid,last_name,balance,lifetime_earned,lifetime_spent,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?)")) {
      statement.setString(1, account.uuid().toString());
      statement.setString(2, truncate(account.lastName(), 16));
      statement.setLong(3, account.balance());
      statement.setLong(4, account.lifetimeEarned());
      statement.setLong(5, account.lifetimeSpent());
      statement.setLong(6, account.createdAt().toEpochMilli());
      statement.setLong(7, account.updatedAt().toEpochMilli());
      statement.executeUpdate();
    } catch (SQLException race) {
      if (!isConstraintViolation(race)) throw race;
    }
  }

  private void updateExistingAccount(Connection connection, PlayerAccount.Snapshot account)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE coin_accounts SET last_name=?,balance=?,lifetime_earned=?,lifetime_spent=?,updated_at=?"
                + " WHERE uuid=?")) {
      bindAccountUpdate(statement, account);
      if (statement.executeUpdate() != 1) throw new SQLException("Account vanished during mutation");
    }
  }

  private void bindAccountUpdate(PreparedStatement statement, PlayerAccount.Snapshot account)
      throws SQLException {
    statement.setString(1, truncate(account.lastName(), 16));
    statement.setLong(2, account.balance());
    statement.setLong(3, account.lifetimeEarned());
    statement.setLong(4, account.lifetimeSpent());
    statement.setLong(5, account.updatedAt().toEpochMilli());
    statement.setString(6, account.uuid().toString());
  }

  private void insertTransaction(Connection connection, GemTransaction transaction)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO coin_transactions(operation_id,uuid,amount,balance_before,balance_after,"
                + "transaction_type,reason,reference_value,status,delivery_error,created_at,completed_at,account_sequence,reward_notified)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      statement.setString(1, transaction.operationId().toString());
      statement.setString(2, transaction.uuid().toString());
      statement.setLong(3, transaction.amount());
      statement.setLong(4, transaction.balanceBefore());
      statement.setLong(5, transaction.balanceAfter());
      statement.setString(6, transaction.type().name());
      statement.setString(7, truncate(transaction.reason(), 128));
      statement.setString(8, truncate(transaction.reference(), 128));
      statement.setString(9, transaction.status().name());
      statement.setNull(10, Types.VARCHAR);
      statement.setLong(11, transaction.createdAt().toEpochMilli());
      if (transaction.completedAt() == null) statement.setNull(12, Types.BIGINT);
      else statement.setLong(12, transaction.completedAt().toEpochMilli());
      statement.setLong(13, transaction.accountSequence());
      statement.setInt(14, transaction.type() == TransactionType.PLAYTIME_REWARD ? 0 : 1);
      statement.executeUpdate();
    }
  }

  @Override
  public Optional<RewardNotification> claimRewardNotification(UUID accountId) throws Exception {
    String claim = UUID.randomUUID().toString();
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        int claimed;
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE coin_transactions SET reward_notification_claim=? WHERE uuid=?"
                + " AND transaction_type='PLAYTIME_REWARD' AND reward_notified=0"
                + " AND reward_notification_claim IS NULL")) {
          statement.setString(1, claim);
          statement.setString(2, accountId.toString());
          claimed = statement.executeUpdate();
        }
        if (claimed == 0) {
          connection.commit();
          return Optional.empty();
        }
        long amount;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM coin_transactions"
                + " WHERE reward_notification_claim=?")) {
          statement.setString(1, claim);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Claimed reward notification vanished");
            amount = result.getLong(1);
          }
        }
        long balance;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT balance FROM coin_accounts WHERE uuid=?")) {
          statement.setString(1, accountId.toString());
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Reward account vanished");
            balance = result.getLong(1);
          }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE coin_transactions SET reward_notified=1,reward_notification_claim=NULL"
                + " WHERE reward_notification_claim=?")) {
          statement.setString(1, claim);
          statement.executeUpdate();
        }
        connection.commit();
        return Optional.of(new RewardNotification(claimed, amount, balance));
      } catch (Exception failure) {
        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
        throw failure;
      } finally {
        try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
      }
    }
  }

  @Override
  public List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT last_name,balance FROM coin_accounts ORDER BY balance DESC,last_name ASC"
                    + " LIMIT ? OFFSET ?")) {
      statement.setInt(1, limit);
      statement.setInt(2, offset);
      try (ResultSet result = statement.executeQuery()) {
        List<LeaderboardEntry> output = new ArrayList<>();
        while (result.next()) output.add(new LeaderboardEntry(result.getString(1), result.getLong(2)));
        return output;
      }
    }
  }

  @Override
  public List<GemTransaction> history(UUID uuid, int offset, int limit) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT * FROM coin_transactions WHERE uuid=? ORDER BY created_at DESC,id DESC"
                    + " LIMIT ? OFFSET ?")) {
      statement.setString(1, uuid.toString());
      statement.setInt(2, limit);
      statement.setInt(3, offset);
      return readTransactions(statement);
    }
  }

  @Override
  public List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT * FROM coin_transactions WHERE status IN ('DELIVERY_PENDING','DELIVERY_STARTED','DELIVERY_FAILED','DELIVERY_FAILED_SAFE','DELIVERY_AMBIGUOUS','DELIVERY_PARTIAL','MANUAL_REVIEW')"
                    + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
      statement.setInt(1, limit);
      statement.setInt(2, offset);
      return readTransactions(statement);
    }
  }

  @Override
  public long countTransactions(TransactionStatus status) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM coin_transactions WHERE status=?")) {
      statement.setString(1, status.name());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  @Override
  public long maxAccountSequence(UUID accountId) throws Exception {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(MAX(account_sequence),0) FROM coin_transactions WHERE uuid=?")) {
      statement.setString(1, accountId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  @Override
  public void resolveManualReview(
      UUID operationId, TransactionStatus newStatus, String adminId, String action, String details)
      throws Exception {
    if (newStatus != TransactionStatus.DELIVERED
        && newStatus != TransactionStatus.REFUNDED
        && newStatus != TransactionStatus.DELIVERY_PENDING) {
      throw new IllegalArgumentException("Unsupported manual resolution");
    }
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        GemTransaction current = findTransaction(connection, operationId)
            .orElseThrow(() -> new SQLException("Unknown operation " + operationId));
        if (current.status() != TransactionStatus.MANUAL_REVIEW) {
          throw new SQLException("Operation is not in MANUAL_REVIEW: " + current.status());
        }
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE coin_transactions SET status=?,delivery_error=?,completed_at=?"
                + " WHERE operation_id=? AND status='MANUAL_REVIEW'")) {
          update.setString(1, newStatus.name());
          update.setString(2, truncate(details, 512));
          if (newStatus == TransactionStatus.DELIVERY_PENDING) update.setNull(3, Types.BIGINT);
          else update.setLong(3, System.currentTimeMillis());
          update.setString(4, operationId.toString());
          if (update.executeUpdate() != 1) throw new SQLException("Concurrent manual resolution");
        }
        try (PreparedStatement audit = connection.prepareStatement(
            "INSERT INTO novacoins_admin_audit(admin_uuid,action,operation_id,old_status,new_status,details,created_at)"
                + " VALUES(?,?,?,?,?,?,?)")) {
          audit.setString(1, truncate(adminId, 36));
          audit.setString(2, truncate(action, 32));
          audit.setString(3, operationId.toString());
          audit.setString(4, current.status().name());
          audit.setString(5, newStatus.name());
          audit.setString(6, truncate(details, 512));
          audit.setLong(7, System.currentTimeMillis());
          audit.executeUpdate();
        }
        connection.commit();
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  @Override
  public DurableMutationResult resolveManualReviewRefund(UUID operationId, String adminId)
      throws Exception {
    UUID refundId = UUID.nameUUIDFromBytes(
        ("novagems:refund:" + operationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        GemTransaction purchase = findTransaction(connection, operationId)
            .orElseThrow(() -> new SQLException("Unknown operation " + operationId));
        Optional<GemTransaction> existingRefund = findTransaction(connection, refundId);
        if (purchase.status() == TransactionStatus.REFUNDED && existingRefund.isPresent()) {
          PlayerAccount.Snapshot account = loadAccount(connection, purchase.uuid(), false)
              .orElseThrow(() -> new SQLException("Refunded account is missing")).snapshot();
          connection.commit();
          return new DurableMutationResult(
              DurableMutationResult.Status.DUPLICATE, account, existingRefund.get());
        }
        if (purchase.status() != TransactionStatus.MANUAL_REVIEW) {
          throw new SQLException("Operation is not in MANUAL_REVIEW: " + purchase.status());
        }
        PlayerAccount.Snapshot account;
        GemTransaction refundTransaction;
        DurableMutationResult.Status resultStatus;
        if (existingRefund.isPresent()) {
          account = loadAccount(connection, purchase.uuid(), true)
              .orElseThrow(() -> new SQLException("Refund account is missing")).snapshot();
          refundTransaction = existingRefund.get();
          resultStatus = DurableMutationResult.Status.DUPLICATE;
        } else {
          PlayerAccount loaded = loadAccount(connection, purchase.uuid(), true)
              .orElseThrow(() -> new SQLException("Refund account is missing"));
          long sequence = maxAccountSequence(connection, purchase.uuid()) + 1;
          EconomyOperation refund = new EconomyOperation(
              refundId, purchase.uuid(), MutationKind.REFUND_DEBIT, purchase.amount(),
              TransactionType.REFUND, "MANUAL_REVIEW_REFUND", operationId.toString(),
              TransactionStatus.REFUNDED, Instant.now(), sequence);
          MutationCalculation calculation = calculate(loaded.snapshot(), refund);
          if (calculation.failure() != null) {
            throw new SQLException("Manual refund calculation failed: " + calculation.failure());
          }
          account = calculation.snapshot();
          updateExistingAccount(connection, account);
          refundTransaction = transaction(refund, loaded.balance(), account.balance());
          insertTransaction(connection, refundTransaction);
          resultStatus = DurableMutationResult.Status.APPLIED;
        }
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE coin_transactions SET status='REFUNDED',delivery_error=?,completed_at=?"
                + " WHERE operation_id=? AND status='MANUAL_REVIEW'")) {
          update.setString(1, "Reembolso manual idempotente confirmado");
          update.setLong(2, System.currentTimeMillis());
          update.setString(3, operationId.toString());
          if (update.executeUpdate() != 1) throw new SQLException("Concurrent manual refund");
        }
        insertAdminAudit(connection, adminId, "REVIEW_REFUND", operationId,
            TransactionStatus.MANUAL_REVIEW, TransactionStatus.REFUNDED,
            "Reembolso manual idempotente confirmado");
        connection.commit();
        return new DurableMutationResult(resultStatus, account, refundTransaction);
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private long maxAccountSequence(Connection connection, UUID accountId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT COALESCE(MAX(account_sequence),0) FROM coin_transactions WHERE uuid=?")) {
      statement.setString(1, accountId.toString());
      try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
    }
  }

  private void insertAdminAudit(
      Connection connection, String adminId, String action, UUID operationId,
      TransactionStatus oldStatus, TransactionStatus newStatus, String details) throws SQLException {
    try (PreparedStatement audit = connection.prepareStatement(
        "INSERT INTO novacoins_admin_audit(admin_uuid,action,operation_id,old_status,new_status,details,created_at)"
            + " VALUES(?,?,?,?,?,?,?)")) {
      audit.setString(1, truncate(adminId, 36));
      audit.setString(2, truncate(action, 32));
      audit.setString(3, operationId.toString());
      audit.setString(4, oldStatus.name());
      audit.setString(5, newStatus.name());
      audit.setString(6, truncate(details, 512));
      audit.setLong(7, System.currentTimeMillis());
      audit.executeUpdate();
    }
  }

  private List<GemTransaction> readTransactions(PreparedStatement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery()) {
      List<GemTransaction> output = new ArrayList<>();
      while (result.next()) output.add(transaction(result));
      return output;
    }
  }

  private GemTransaction transaction(ResultSet result) throws SQLException {
    long completedMillis = result.getLong("completed_at");
    Instant completed = result.wasNull() ? null : Instant.ofEpochMilli(completedMillis);
    return new GemTransaction(
        result.getLong("id"),
        UUID.fromString(result.getString("operation_id")),
        UUID.fromString(result.getString("uuid")),
        result.getLong("amount"),
        result.getLong("balance_before"),
        result.getLong("balance_after"),
        TransactionType.valueOf(result.getString("transaction_type")),
        result.getString("reason"),
        result.getString("reference_value"),
        TransactionStatus.valueOf(result.getString("status")),
        result.getString("delivery_error"),
        Instant.ofEpochMilli(result.getLong("created_at")),
        completed,
        result.getLong("account_sequence"));
  }

  private PlayerAccount account(ResultSet result) throws SQLException {
    return new PlayerAccount(
        UUID.fromString(result.getString("uuid")),
        result.getString("last_name"),
        result.getLong("balance"),
        result.getLong("lifetime_earned"),
        result.getLong("lifetime_spent"),
        Instant.ofEpochMilli(result.getLong("created_at")),
        Instant.ofEpochMilli(result.getLong("updated_at")));
  }

  private static boolean isConstraintViolation(SQLException exception) {
    return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
  }

  private static String truncate(String value, int length) {
    if (value == null) return null;
    return value.length() <= length ? value : value.substring(0, length);
  }

  private record MutationCalculation(
      PlayerAccount.Snapshot snapshot, DurableMutationResult.Status failure) {
    static MutationCalculation succeeded(PlayerAccount.Snapshot snapshot) {
      return new MutationCalculation(snapshot, null);
    }

    static MutationCalculation failed(DurableMutationResult.Status status) {
      return new MutationCalculation(null, status);
    }
  }
}
