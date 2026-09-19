package net.watones.novagems.listener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.session.SessionService;
import net.watones.novagems.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerConnectionListener implements Listener {
  private final JavaPlugin plugin;
  private final WalletService wallets;
  private final SessionService sessions;
  private final ShopService shop;
  private final Set<UUID> connected = ConcurrentHashMap.newKeySet();

  public PlayerConnectionListener(
      JavaPlugin plugin, WalletService wallets, SessionService sessions, ShopService shop) {
    this.plugin = plugin;
    this.wallets = wallets;
    this.sessions = sessions;
    this.shop = shop;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    start(e.getPlayer());
  }

  public void start(Player player) {
    connected.add(player.getUniqueId());
    sessions.connect(player.getUniqueId());
    loadAttempt(player.getUniqueId(), player.getName(), 1);
  }

  private void loadAttempt(UUID uuid, String name, int attempt) {
    wallets
        .load(uuid, name)
        .whenComplete(
            (account, error) -> {
              if (!plugin.isEnabled()) return;
              Bukkit.getScheduler()
                  .runTask(
                      plugin,
                      () -> {
                        if (!connected.contains(uuid)) {
                          wallets.unload(uuid);
                          return;
                        }
                        if (error == null) {
                          Player online = Bukkit.getPlayer(uuid);
                          if (online != null) {
                            sessions.notifyPendingRewards(uuid);
                            shop.recoverPending(online);
                          }
                          return;
                        }
                        if (attempt < 3) {
                          Bukkit.getScheduler()
                              .runTaskLater(
                                  plugin, () -> loadAttempt(uuid, name, attempt + 1), 100L);
                        } else {
                          plugin
                              .getLogger()
                              .log(
                                  Level.SEVERE,
                                  "No se pudo cargar la cuenta de "
                                      + uuid
                                      + " tras 3 intentos; las operaciones quedan bloqueadas",
                                  error);
                        }
                      });
            });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    end(e.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void kick(PlayerKickEvent e) {
    end(e.getPlayer());
  }

  private void end(Player player) {
    connected.remove(player.getUniqueId());
    sessions.disconnect(player.getUniqueId());
    wallets.unload(player.getUniqueId());
  }
}
