package net.watones.novagems.economy;

public enum TransactionStatus {
  COMMITTED,
  DELIVERY_PENDING,
  DELIVERY_STARTED,
  DELIVERED,
  /** Legacy v1.1.0 state. It is treated as a known-safe failure during migration/recovery. */
  DELIVERY_FAILED,
  DELIVERY_FAILED_SAFE,
  DELIVERY_AMBIGUOUS,
  DELIVERY_PARTIAL,
  REFUNDED,
  MANUAL_REVIEW;

  public boolean terminal() {
    return switch (this) {
      case COMMITTED, DELIVERED, DELIVERY_PARTIAL, REFUNDED, MANUAL_REVIEW -> true;
      case DELIVERY_PENDING, DELIVERY_STARTED, DELIVERY_FAILED, DELIVERY_FAILED_SAFE,
          DELIVERY_AMBIGUOUS -> false;
    };
  }

  public boolean needsSafeRefund() {
    return this == DELIVERY_FAILED || this == DELIVERY_FAILED_SAFE;
  }
}
