package net.watones.novagems.shop.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class ConfirmMenuHolder implements InventoryHolder {
  private final String rewardId;
  private final long snapshotVersion;
  private final int page;
  private final String category;
  private Inventory inventory;

  public ConfirmMenuHolder(String rewardId, long snapshotVersion, int page) {
    this(rewardId, snapshotVersion, page, "all");
  }

  public ConfirmMenuHolder(String rewardId, long snapshotVersion, int page, String category) {
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

  public String category() { return category; }

  public void inventory(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public @NotNull Inventory getInventory() {
    if (inventory == null) throw new IllegalStateException("Inventory not initialized");
    return inventory;
  }
}
