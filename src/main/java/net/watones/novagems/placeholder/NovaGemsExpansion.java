package net.watones.novagems.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.session.*;
import net.watones.novagems.util.Formatters;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class NovaGemsExpansion extends PlaceholderExpansion {
  private final WalletService wallets;
  private final SessionRegistry sessions;
  private final ConfigManager config;

  public NovaGemsExpansion(WalletService wallets, SessionRegistry sessions, ConfigManager config) {
    this.wallets = wallets;
    this.sessions = sessions;
    this.config = config;
  }

  @Override
  public @NotNull String getIdentifier() {
    return "novagems";
  }

  @Override
  public @NotNull String getAuthor() {
    return "Watones";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.1.1";
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
    if (player == null) return "";
    var account = wallets.account(player.getUniqueId());
    return switch (params.toLowerCase()) {
      case "balance" -> account.map(a -> Long.toString(a.balance())).orElse("");
      case "balance_formatted" -> account.map(a -> Formatters.number(a.balance())).orElse("");
      case "lifetime_earned" -> account.map(a -> Long.toString(a.lifetimeEarned())).orElse("");
      case "lifetime_spent" -> account.map(a -> Long.toString(a.lifetimeSpent())).orElse("");
      case "session_elapsed", "cycle_elapsed" ->
          sessions
              .get(player.getUniqueId())
              .map(s -> Formatters.duration(s.timer().elapsedNanos() / 1_000_000_000L))
              .orElse("");
      case "session_remaining", "cycle_remaining" ->
          sessions
              .get(player.getUniqueId())
              .map(
                  s ->
                      Formatters.duration(
                          Math.max(
                              0,
                              config.current().intervalSeconds()
                                  - s.timer().elapsedNanos() / 1_000_000_000L)))
              .orElse("");
      case "session_cycles" ->
          sessions.get(player.getUniqueId()).map(s -> Long.toString(s.timer().cycles())).orElse("");
      case "account_state" -> wallets.accountState(player.getUniqueId()).name();
      case "storage_health" -> wallets.health().name();
      case "reward_progress_percent" ->
          sessions
              .get(player.getUniqueId())
              .map(
                  session ->
                      Long.toString(
                          Math.min(
                              100,
                              Math.round(
                                  session.timer().elapsedNanos()
                                      * 100.0
                                      / (config.current().intervalSeconds()
                                          * 1_000_000_000L)))))
              .orElse("");
      default -> null;
    };
  }
}
