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
    if (!(holder instanceof ShopMenuHolder)
        && !(holder instanceof ConfirmMenuHolder)
        && !(holder instanceof QuantityMenuHolder)
        && !(holder instanceof StackPickerHolder)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player player)
        || e.getClickedInventory() != e.getView().getTopInventory()) return;
    if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
    if (holder instanceof ShopMenuHolder menu) shop.select(player, menu, e.getRawSlot());
    else if (holder instanceof ConfirmMenuHolder confirm) {
      if (e.getRawSlot() == 15) shop.confirm(player, confirm);
      else if (e.getRawSlot() == 11) shop.open(player, confirm.page(), confirm.category());
    } else if (holder instanceof QuantityMenuHolder quantity) {
      shop.handleQuantityClick(player, quantity, e.getRawSlot());
    } else if (holder instanceof StackPickerHolder picker) {
      shop.handleStackPick(player, picker, e.getRawSlot());
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    Object holder = e.getView().getTopInventory().getHolder();
    if (holder instanceof ShopMenuHolder
        || holder instanceof ConfirmMenuHolder
        || holder instanceof QuantityMenuHolder
        || holder instanceof StackPickerHolder) e.setCancelled(true);
  }
}
