package net.watones.novagems.shop;

public final class PurchasePolicy {
  private PurchasePolicy() {}

  public enum Decision {
    ALLOW,
    INSUFFICIENT_FUNDS,
    INVENTORY_FULL,
    INVALID_PRICE
  }

  public enum ConfirmationChoice {
    CONFIRM,
    CANCEL,
    CLOSE
  }

  public static Decision validate(long balance, long price, boolean inventoryFits) {
    if (price < 1) return Decision.INVALID_PRICE;
    if (balance < price) return Decision.INSUFFICIENT_FUNDS;
    if (!inventoryFits) return Decision.INVENTORY_FULL;
    return Decision.ALLOW;
  }

  public static boolean shouldCharge(boolean confirmationRequired, ConfirmationChoice choice) {
    return !confirmationRequired || choice == ConfirmationChoice.CONFIRM;
  }
}
