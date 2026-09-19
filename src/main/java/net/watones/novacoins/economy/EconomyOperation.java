package net.watones.novacoins.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EconomyOperation(
    UUID operationId, UUID accountId, MutationKind kind, long amount, TransactionType type,
    String reason, String reference, TransactionStatus initialStatus, Instant createdAt,
    long accountSequence) {
  public EconomyOperation {
    Objects.requireNonNull(operationId); Objects.requireNonNull(accountId); Objects.requireNonNull(kind);
    Objects.requireNonNull(type); Objects.requireNonNull(reason); Objects.requireNonNull(initialStatus);
    Objects.requireNonNull(createdAt); reference = reference == null ? "" : reference;
    if (accountSequence < 0) throw new IllegalArgumentException("Negative account sequence");
  }
  /** Compatibility constructor for v1.1.1 callers and journal records. Sequence is assigned at capture. */
  public EconomyOperation(UUID operationId, UUID accountId, MutationKind kind, long amount,
      TransactionType type, String reason, String reference, TransactionStatus initialStatus,
      Instant createdAt) {
    this(operationId, accountId, kind, amount, type, reason, reference, initialStatus, createdAt, 0);
  }
  public static EconomyOperation create(UUID accountId, MutationKind kind, long amount,
      TransactionType type, String reason, String reference, TransactionStatus status) {
    return new EconomyOperation(UUID.randomUUID(), accountId, kind, amount, type, reason, reference,
        status, Instant.now(), 0);
  }

  public EconomyOperation withAccountSequence(long sequence) {
    return new EconomyOperation(operationId, accountId, kind, amount, type, reason, reference,
        initialStatus, createdAt, sequence);
  }
}
