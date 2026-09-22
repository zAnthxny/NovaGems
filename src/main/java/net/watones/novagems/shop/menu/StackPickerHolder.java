package net.watones.novagems.shop.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** "Comprar por STACKS" submenu: pick a whole number of stacks (1-9) at once. */
public final class StackPickerHolder implements InventoryHolder {
  private final String rewardId;
  private final long snapshotVersion;
  private final int page;
  private final String category;
  private Inventory inventory;

  public StackPickerHolder(String rewardId, long snapshotVersion, int page, String category) {
    this.rewardId = rewardId;
    this.snapshotVersion = snapshotVersion;
    this.page = page;
    this.category = category;
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

  public void inventory(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public @NotNull Inventory getInventory() {
    if (inventory == null) throw new IllegalStateException("Inventory not initialized");
    return inventory;
  }
}
