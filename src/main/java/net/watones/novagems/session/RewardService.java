package net.watones.novagems.session;

import java.util.Map;
import java.util.UUID;
import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.message.MessageService;
import net.watones.novagems.util.Formatters;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardService {
  private final JavaPlugin plugin;
  private final WalletService wallets;
  private final ConfigManager config;
  private final MessageService messages;

  public RewardService(
      JavaPlugin plugin, WalletService wallets, ConfigManager config, MessageService messages) {
    this.plugin = plugin;
    this.wallets = wallets;
    this.config = config;
    this.messages = messages;
    wallets.onRecoveredRewardReady(this::notifyPending);
  }

  public WalletService.OperationSubmission capture(CompletedReward reward) {
    EconomyOperation operation =
        new EconomyOperation(
            reward.operationId(), reward.playerUuid(), MutationKind.CREDIT, reward.amount(),
            TransactionType.PLAYTIME_REWARD, "SESSION_INTERVAL", reward.reference(),
            TransactionStatus.COMMITTED, reward.completedAt());
    WalletService.OperationSubmission submission = wallets.captureRetriable(operation);
    submission.result().thenAccept(result -> {
      if (!result.success()) return;
      notifyPending(reward.playerUuid());
    });
    return submission;
  }

  /** Leaves the durable notification untouched while offline and atomically claims it when online. */
  public void notifyPending(UUID uuid) {
    Bukkit.getScheduler().runTask(plugin, () -> {
      Player player = Bukkit.getPlayer(uuid);
      if (player == null || !player.isOnline()) return;
      wallets.claimRewardNotification(uuid).whenComplete((claimed, error) -> {
        if (error != null || claimed.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> notifyPlayer(uuid, claimed.orElseThrow()));
      });
    });
  }

  private void notifyPlayer(UUID uuid, net.watones.novagems.storage.StorageProvider.RewardNotification notice) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null || !player.isOnline()) return;
    var notification = config.current().notification();
    Map<String, String> values = Map.of(
        "amount", Formatters.number(notice.amount()),
        "rewards", Formatters.number(notice.rewards()),
        "interval", Formatters.duration(config.current().intervalSeconds()),
        "balance", Formatters.number(notice.balance()));
    String key = notice.rewards() == 1 ? "reward" : "reward-recovered";
    if (notification.chat()) player.sendMessage(messages.component(key, values));
    if (notification.actionbar()) player.sendActionBar(messages.component(key, values));
    if (notification.sound()) {
      player.playSound(player.getLocation(), notification.soundName(), notification.volume(), notification.pitch());
    }
  }
}
