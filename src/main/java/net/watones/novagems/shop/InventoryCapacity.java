package net.watones.novagems.shop;

import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class InventoryCapacity {
  private InventoryCapacity() {}

  public static boolean canFit(PlayerInventory inventory, List<ItemStack> additions) {
    ItemStack[] original = inventory.getStorageContents();
    ItemStack[] simulated = new ItemStack[original.length];
    for (int i = 0; i < original.length; i++)
      simulated[i] = original[i] == null ? null : original[i].clone();
    for (ItemStack add : additions) {
      int remaining = add.getAmount();
      for (ItemStack existing : simulated)
        if (existing != null && existing.isSimilar(add)) {
          int room = existing.getMaxStackSize() - existing.getAmount();
          int moved = Math.min(room, remaining);
          existing.setAmount(existing.getAmount() + moved);
          remaining -= moved;
          if (remaining == 0) break;
        }
      for (int i = 0; i < simulated.length && remaining > 0; i++)
        if (simulated[i] == null || simulated[i].getType().isAir()) {
          int moved = Math.min(add.getMaxStackSize(), remaining);
          ItemStack clone = add.clone();
          clone.setAmount(moved);
          simulated[i] = clone;
          remaining -= moved;
        }
      if (remaining > 0) return false;
    }
    return true;
  }
}
