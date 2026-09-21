package net.watones.novagems.shop.listener;

import net.watones.novagems.shop.ShopService;
import net.watones.novagems.shop.menu.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;

public final class ShopListener implements Listener {
  private final ShopService shop;

  public ShopListener(ShopService shop) {
    this.shop = shop;
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    Object holder = e.getView().getTopInventory().getHolder();
    if (!(holder instanceof ShopMenuHolder) && !(holder instanceof ConfirmMenuHolder)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player player)
        || e.getClickedInventory() != e.getView().getTopInventory()) return;
    if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
    if (holder instanceof ShopMenuHolder menu) shop.select(player, menu, e.getRawSlot());
    else if (holder instanceof ConfirmMenuHolder confirm) {
      if (e.getRawSlot() == 15) shop.confirm(player, confirm);
      else if (e.getRawSlot() == 11) shop.open(player, confirm.page(), confirm.category());
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    Object holder = e.getView().getTopInventory().getHolder();
    if (holder instanceof ShopMenuHolder || holder instanceof ConfirmMenuHolder)
      e.setCancelled(true);
  }
}
