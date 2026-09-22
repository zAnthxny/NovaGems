package net.watones.novagems.listener;

import java.util.UUID;
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

/** Credits a killer for eliminating another player, bounded by a per-day limit. Silent by design. */
public final class KillRewardListener implements Listener {
  private final WalletService wallets;
  private final ConfigManager config;
  private final DailyKillTracker tracker;

  public KillRewardListener(
      WalletService wallets, ConfigManager config, DailyKillTracker tracker) {
    this.wallets = wallets;
    this.config = config;
    this.tracker = tracker;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    Player killer = event.getEntity().getKiller();
    if (killer == null || killer.getUniqueId().equals(event.getEntity().getUniqueId())) return;
    RuntimeConfig.KillRewards settings = config.current().killRewards();
    if (!settings.enabled()) return;
    UUID killerId = killer.getUniqueId();
    UUID victimId = event.getEntity().getUniqueId();
    if (tracker.registerKill(killerId, victimId, settings.dailyLimit()) < 0) return;
    // Remember the pair before crediting: if the write loses a race with a restart the worst case
    // is one kill that could be earned again, never a gem that was paid twice.
    wallets.recordDailyKill(killerId, victimId, tracker.todayKey());
    wallets.credit(
        killerId,
        settings.gemsPerKill(),
        TransactionType.KILL_REWARD,
        "PLAYER_KILL",
        "kill:" + victimId);
  }
}
