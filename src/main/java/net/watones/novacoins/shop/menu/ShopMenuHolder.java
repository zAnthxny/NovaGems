package net.watones.novacoins.shop.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class ShopMenuHolder implements InventoryHolder {
  private final long snapshotVersion;
  private final int page;
  private final String category;
  private Inventory inventory;

  public ShopMenuHolder(long snapshotVersion, int page) {
    this(snapshotVersion, page, "all");
  }

  public ShopMenuHolder(long snapshotVersion, int page, String category) {
    this.snapshotVersion = snapshotVersion;
    this.page = page;
    this.category = category;
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
