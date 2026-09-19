package net.watones.novagems.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.watones.novagems.economy.GemTransaction;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.PlayerAccount;
import net.watones.novagems.economy.TransactionStatus;

public interface StorageProvider extends AutoCloseable {
  void initialize() throws Exception;

  Optional<PlayerAccount> loadAccount(UUID uuid) throws Exception;

  Optional<UUID> findUuidByName(String name) throws Exception;

  PlayerAccount loadOrCreate(UUID uuid, String name) throws Exception;

  DurableMutationResult applyOperation(EconomyOperation operation) throws Exception;

  void markDelivery(UUID operationId, TransactionStatus status, String error) throws Exception;

  Optional<GemTransaction> findTransaction(UUID operationId) throws Exception;

  List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception;

  List<GemTransaction> history(UUID uuid, int offset, int limit) throws Exception;

  List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception;

  long countTransactions(TransactionStatus status) throws Exception;

  default long maxAccountSequence(UUID accountId) throws Exception { return 0; }

  /** Atomically claims and acknowledges all outstanding playtime notices for one account. */
  default Optional<RewardNotification> claimRewardNotification(UUID accountId) throws Exception {
    return Optional.empty();
  }

  default void resolveManualReview(
      UUID operationId, TransactionStatus newStatus, String adminId, String action, String details)
      throws Exception {
    throw new UnsupportedOperationException("Manual review resolution is not supported");
  }

  default DurableMutationResult resolveManualReviewRefund(UUID operationId, String adminId)
      throws Exception {
    throw new UnsupportedOperationException("Manual review refund is not supported");
  }

  int workerThreads();

  String description();

  @Override
  void close() throws Exception;

  record LeaderboardEntry(String name, long balance) {}

  record RewardNotification(int rewards, long amount, long balance) {}
}
