package net.watones.novagems.economy;

public final class DeliveryStateMachine {
  private DeliveryStateMachine() {}

  public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
    if (from == to) return true;
    return switch (from) {
      case DELIVERY_PENDING ->
          to == TransactionStatus.DELIVERY_STARTED
              || to == TransactionStatus.DELIVERY_FAILED
              || to == TransactionStatus.DELIVERY_FAILED_SAFE;
      case DELIVERY_STARTED ->
          to == TransactionStatus.DELIVERED
              || to == TransactionStatus.DELIVERY_FAILED_SAFE
              || to == TransactionStatus.DELIVERY_AMBIGUOUS
              || to == TransactionStatus.DELIVERY_PARTIAL
              || to == TransactionStatus.MANUAL_REVIEW;
      case DELIVERY_FAILED, DELIVERY_FAILED_SAFE -> to == TransactionStatus.REFUNDED;
      case DELIVERY_AMBIGUOUS -> to == TransactionStatus.MANUAL_REVIEW;
      case COMMITTED, DELIVERED, DELIVERY_PARTIAL, REFUNDED, MANUAL_REVIEW -> false;
    };
  }
}
