package net.watones.novagems.listener;

import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.config.RuntimeConfig;
import net.watones.novagems.economy.TransactionType;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.session.DailyKillTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Credits a killer for eliminating another player, bounded by a per-day limit. Silent by design — no chat spam. */
public final class KillRewardListener implements Listener {
  private final WalletService wallets;
  private final ConfigManager config;
  private final DailyKillTracker tracker = new DailyKillTracker();

  public KillRewardListener(WalletService wallets, ConfigManager config) {
    this.wallets = wallets;
    this.config = config;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    Player killer = event.getEntity().getKiller();
    if (killer == null || killer.getUniqueId().equals(event.getEntity().getUniqueId())) return;
    RuntimeConfig.KillRewards settings = config.current().killRewards();
    if (!settings.enabled()) return;
    int resultingCount =
        tracker.registerKill(
            killer.getUniqueId(), event.getEntity().getUniqueId(), settings.dailyLimit());
    if (resultingCount < 0) return;
    wallets.credit(
        killer.getUniqueId(),
        settings.gemsPerKill(),
        TransactionType.KILL_REWARD,
        "PLAYER_KILL",
        "kill:" + event.getEntity().getUniqueId());
  }
}
