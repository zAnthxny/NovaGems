package net.watones.novagems.listener;

import java.util.Map;
import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.config.RuntimeConfig;
import net.watones.novagems.economy.TransactionType;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.message.MessageService;
import net.watones.novagems.session.DailyKillTracker;
import net.watones.novagems.util.Formatters;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Credits a killer for eliminating another player, bounded by a per-day limit. */
public final class KillRewardListener implements Listener {
  private final JavaPlugin plugin;
  private final WalletService wallets;
  private final ConfigManager config;
  private final MessageService messages;
  private final DailyKillTracker tracker = new DailyKillTracker();

  public KillRewardListener(
      JavaPlugin plugin, WalletService wallets, ConfigManager config, MessageService messages) {
    this.plugin = plugin;
    this.wallets = wallets;
    this.config = config;
    this.messages = messages;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    Player killer = event.getEntity().getKiller();
    if (killer == null || killer.getUniqueId().equals(event.getEntity().getUniqueId())) return;
    RuntimeConfig.KillRewards settings = config.current().killRewards();
    if (!settings.enabled()) return;
    int resultingCount = tracker.registerKill(killer.getUniqueId(), settings.dailyLimit());
    if (resultingCount < 0) return;
    long amount = settings.gemsPerKill();
    wallets
        .credit(
            killer.getUniqueId(),
            amount,
            TransactionType.KILL_REWARD,
            "PLAYER_KILL",
            "kill:" + event.getEntity().getUniqueId())
        .thenAccept(
            result ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!result.success() || !killer.isOnline()) return;
                          String limit =
                              settings.dailyLimit() > 0
                                  ? Integer.toString(settings.dailyLimit())
                                  : "∞";
                          messages.send(
                              killer,
                              "kill-reward",
                              Map.of(
                                  "amount", Formatters.number(amount),
                                  "kills", Integer.toString(resultingCount),
                                  "limit", limit,
                                  "balance", Formatters.number(result.balanceAfter())));
                          if (settings.dailyLimit() > 0
                              && resultingCount == settings.dailyLimit()) {
                            messages.send(
                                killer,
                                "kill-reward-limit-reached",
                                Map.of("limit", limit));
                          }
                        }));
  }
}
