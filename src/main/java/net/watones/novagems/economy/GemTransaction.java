package net.watones.novagems.economy;

import java.time.Instant;
import java.util.UUID;

public record GemTransaction(
    long id, UUID operationId, UUID uuid, long amount, long balanceBefore, long balanceAfter,
    TransactionType type, String reason, String reference, TransactionStatus status,
    String deliveryError, Instant createdAt, Instant completedAt, long accountSequence) {
  public GemTransaction(long id, UUID operationId, UUID uuid, long amount, long balanceBefore,
      long balanceAfter, TransactionType type, String reason, String reference,
      TransactionStatus status, String deliveryError, Instant createdAt, Instant completedAt) {
    this(id, operationId, uuid, amount, balanceBefore, balanceAfter, type, reason, reference,
        status, deliveryError, createdAt, completedAt, 0);
  }
}
