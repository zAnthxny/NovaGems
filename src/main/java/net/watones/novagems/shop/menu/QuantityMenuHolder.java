package net.watones.novagems.shop.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Bulk-quantity purchase menu for stackable item rewards (+1/+10/+64, -1/-10/reset, confirm). */
public final class QuantityMenuHolder implements InventoryHolder {
  private final String rewardId;
  private final long snapshotVersion;
  private final int page;
  private final String category;
  private final int quantity;
  private Inventory inventory;

  public QuantityMenuHolder(
      String rewardId, long snapshotVersion, int page, String category, int quantity) {
    this.rewardId = rewardId;
    this.snapshotVersion = snapshotVersion;
    this.page = page;
    this.category = category;
    this.quantity = quantity;
  }

  public String rewardId() {
    return rewardId;
  }

  public long snapshotVersion() {
    return snapshotVersion;
  }

  public int page() {
    return page;
  }

  public String category() {
    return category;
  }

  public int quantity() {
    return quantity;
  }

  public void inventory(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public @NotNull Inventory getInventory() {
    if (inventory == null) throw new IllegalStateException("Inventory not initialized");
    return inventory;
  }
}
