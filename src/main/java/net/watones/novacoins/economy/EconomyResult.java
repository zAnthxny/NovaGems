package net.watones.novacoins.economy;

import java.util.UUID;

public record EconomyResult(Status status, UUID operationId, long balanceBefore, long balanceAfter,
                            boolean durable, boolean recoveryPending) {
  public enum Status { SUCCESS, DUPLICATE, ACCOUNT_NOT_READY, BUSY, INSUFFICIENT_FUNDS,
    INVALID_AMOUNT, OVERFLOW, STORAGE_UNAVAILABLE, JOURNAL_UNAVAILABLE, RECOVERY_PENDING,
    ADMIN_PENDING, ADMIN_ALREADY_PENDING, SHUTTING_DOWN }
  public boolean success() { return status == Status.SUCCESS || status == Status.DUPLICATE; }
  public static EconomyResult failed(Status status, UUID operationId) {
    return new EconomyResult(status, operationId, 0, 0, false, false);
  }
}
