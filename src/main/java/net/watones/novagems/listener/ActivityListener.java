package net.watones.novagems.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.watones.novagems.activity.*;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

public final class ActivityListener implements Listener {
  private final ActivityGuard guard;

  public ActivityListener(ActivityGuard guard) {
    this.guard = guard;
  }

  private void record(PlayerEvent e, ActivitySignal signal, int pattern) {
    guard.record(e.getPlayer().getUniqueId(), signal, pattern, System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    guard.record(
        e.getPlayer().getUniqueId(),
        ActivitySignal.BLOCK_BREAK,
        e.getBlock().getLocation().hashCode(),
        System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    guard.record(
        e.getPlayer().getUniqueId(),
        ActivitySignal.BLOCK_PLACE,
        e.getBlock().getLocation().hashCode(),
        System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    record(
        e,
        ActivitySignal.INTERACT,
        e.getAction().ordinal() * 31
            + (e.getClickedBlock() == null ? 0 : e.getClickedBlock().getType().ordinal()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent e) {
    record(e, ActivitySignal.COMMAND, e.getMessage().split(" ")[0].hashCode());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void chat(AsyncChatEvent e) {
    guard.record(
        e.getPlayer().getUniqueId(),
        ActivitySignal.CHAT,
        e.message().hashCode(),
        System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void held(PlayerItemHeldEvent e) {
    record(e, ActivitySignal.ITEM_CHANGE, e.getNewSlot());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void inventory(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof org.bukkit.entity.Player p)
      guard.record(
          p.getUniqueId(),
          ActivitySignal.INVENTORY,
          e.getInventory().getType().ordinal(),
          System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void damage(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof org.bukkit.entity.Player p)
      guard.record(
          p.getUniqueId(), ActivitySignal.ATTACK, e.getEntityType().ordinal(), System.nanoTime());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    Location from = e.getFrom(), to = e.getTo();
    if (to == null) return;
    double dx = to.getX() - from.getX();
    double dy = to.getY() - from.getY();
    double dz = to.getZ() - from.getZ();
    float rawYaw = to.getYaw() - from.getYaw();
    if (dx == 0.0 && dy == 0.0 && dz == 0.0 && rawYaw == 0.0f) return;
    float yaw = Math.abs(to.getYaw() - from.getYaw());
    if (dy > .18) record(e, ActivitySignal.JUMP, (int) Math.round(dy * 100));
    else if (yaw > 15) record(e, ActivitySignal.ROTATION, (int) (yaw / 5));
    else if (dx * dx + dy * dy + dz * dz > .04)
      record(e, ActivitySignal.NORMAL, to.getBlockX() * 31 + to.getBlockZ());
  }
}
