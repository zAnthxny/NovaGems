package net.watones.novagems.economy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded one-operation-per-account registry backed by durable recovery records. */
public final class PendingAdministrativeOperations {
  private final int capacity;
  private final Map<UUID, PendingOperation> byAccount = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> accountsByOperation = new ConcurrentHashMap<>();

  public PendingAdministrativeOperations(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
    this.capacity = capacity;
  }

  public synchronized Registration register(EconomyOperation operation) {
    if (!isAdministrative(operation.type())) throw new IllegalArgumentException("Not administrative");
    PendingOperation existing = byAccount.get(operation.accountId());
    if (existing != null) {
      return existing.operationId().equals(operation.operationId())
          ? Registration.DUPLICATE : Registration.CONFLICT;
    }
    if (byAccount.size() >= capacity) return Registration.FULL;
    PendingOperation pending = new PendingOperation(
        operation.accountId(), operation.operationId(), operation.kind(), operation.type(),
        operation.amount(), operation.reference(), operation.createdAt(), operation.accountSequence());
    byAccount.put(operation.accountId(), pending);
    accountsByOperation.put(operation.operationId(), operation.accountId());
    return Registration.ADDED;
  }

  public synchronized Optional<PendingOperation> resolve(UUID operationId) {
    UUID accountId = accountsByOperation.remove(operationId);
    if (accountId == null) return Optional.empty();
    PendingOperation removed = byAccount.get(accountId);
    if (removed != null && removed.operationId().equals(operationId)) byAccount.remove(accountId);
    return Optional.ofNullable(removed);
  }

  public boolean isPending(UUID accountId) { return byAccount.containsKey(accountId); }
  public Optional<PendingOperation> pending(UUID accountId) {
    return Optional.ofNullable(byAccount.get(accountId));
  }
  public Optional<UUID> operationId(UUID accountId) {
    return pending(accountId).map(PendingOperation::operationId);
  }
  public Optional<TransactionType> type(UUID accountId) {
    return pending(accountId).map(PendingOperation::type);
  }
  public Optional<Long> amount(UUID accountId) {
    return pending(accountId).map(PendingOperation::amount);
  }
  public Optional<String> admin(UUID accountId) {
    return pending(accountId).map(PendingOperation::admin);
  }
  public Optional<Instant> createdAt(UUID accountId) {
    return pending(accountId).map(PendingOperation::createdAt);
  }
  public int size() { return byAccount.size(); }
  public int capacity() { return capacity; }

  public static boolean isAdministrative(TransactionType type) {
    return type == TransactionType.ADMIN_GIVE || type == TransactionType.ADMIN_TAKE
        || type == TransactionType.ADMIN_SET || type == TransactionType.ADMIN_RESET;
  }

  public enum Registration { ADDED, DUPLICATE, CONFLICT, FULL }

  public record PendingOperation(
      UUID accountId,
      UUID operationId,
      MutationKind kind,
      TransactionType type,
      long amount,
      String admin,
      Instant createdAt,
      long accountSequence) {}
}
