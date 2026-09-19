package net.watones.novacoins.command;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.watones.novacoins.alert.DiscordWebhookAlertService;
import net.watones.novacoins.config.ConfigManager;
import net.watones.novacoins.config.RuntimeConfig;
import net.watones.novacoins.config.ShopConfig;
import net.watones.novacoins.economy.EconomyResult;
import net.watones.novacoins.economy.CoinTransaction;
import net.watones.novacoins.economy.MutationKind;
import net.watones.novacoins.economy.PlayerAccount;
import net.watones.novacoins.economy.TransactionType;
import net.watones.novacoins.economy.TransactionStatus;
import net.watones.novacoins.economy.WalletService;
import net.watones.novacoins.message.MessageService;
import net.watones.novacoins.shop.ShopService;
import net.watones.novacoins.session.SessionService;
import net.watones.novacoins.util.Formatters;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** The single public command surface for NovaCoins. */
public final class WataCoinsCommand implements CommandExecutor, TabCompleter {
  private static final List<String> ADMIN_ACTIONS = List.of(
      "give", "take", "set", "reset", "reload", "status", "review", "recovery");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final long CONFIRMATION_NANOS = Duration.ofSeconds(30).toNanos();

  private final JavaPlugin plugin;
  private final WalletService wallets;
  private final MessageService messages;
  private final ConfigManager config;
  private final ShopConfig shopConfig;
  private final ShopService shop;
  private final RuntimeConfig.StorageSettings activeStorageSettings;
  private final RuntimeConfig.QueueLimits activeQueueLimits;
  private final int activeLeaderboardCacheSeconds;
  private final RuntimeConfig.RecoverySettings activeRecoverySettings;
  private final SessionService sessions;
  private final String storageDescription;
  private final DiscordWebhookAlertService alerts;
  private final Consumer<RuntimeConfig.AlertSettings> alertReload;
  private final Map<ConfirmationKey, Long> confirmations = new ConcurrentHashMap<>();

  public WataCoinsCommand(
      JavaPlugin plugin,
      WalletService wallets,
      MessageService messages,
      ConfigManager config,
      ShopConfig shopConfig,
      ShopService shop,
      RuntimeConfig.StorageSettings activeStorageSettings,
      RuntimeConfig.QueueLimits activeQueueLimits,
      int activeLeaderboardCacheSeconds,
      RuntimeConfig.RecoverySettings activeRecoverySettings,
      SessionService sessions,
      String storageDescription,
      DiscordWebhookAlertService alerts,
      Consumer<RuntimeConfig.AlertSettings> alertReload) {
    this.plugin = plugin;
    this.wallets = wallets;
    this.messages = messages;
    this.config = config;
    this.shopConfig = shopConfig;
    this.shop = shop;
    this.activeStorageSettings = activeStorageSettings;
    this.activeQueueLimits = activeQueueLimits;
    this.activeLeaderboardCacheSeconds = activeLeaderboardCacheSeconds;
    this.activeRecoverySettings = activeRecoverySettings;
    this.sessions = sessions;
    this.storageDescription = storageDescription;
    this.alerts = alerts;
    this.alertReload = alertReload;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      openShop(sender);
      return true;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "help" -> showHelp(sender);
      case "balance" -> showBalance(sender, args);
      case "admin" -> executeAdmin(sender, args);
      default -> messages.send(sender, "watacoins-unknown");
    }
    return true;
  }

  private void openShop(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      messages.send(sender, "player-only");
      return;
    }
    if (!sender.hasPermission("novacoins.shop")) {
      messages.send(sender, "no-permission");
      return;
    }
    shop.open(player);
  }

  private void showBalance(CommandSender sender, String[] args) {
    if (args.length != 1) {
      messages.send(sender, "watacoins-balance-usage");
      return;
    }
    if (!(sender instanceof Player player)) {
      messages.send(sender, "player-only");
      return;
    }
    if (!sender.hasPermission("novacoins.balance")) {
      messages.send(sender, "no-permission");
      return;
    }
    wallets.account(player.getUniqueId()).ifPresentOrElse(
        account -> messages.send(player, "balance",
            Map.of("balance", Formatters.number(account.balance()))),
        () -> messages.send(player, "account-loading"));
  }

  private void showHelp(CommandSender sender) {
    sender.sendMessage(messages.component("watacoins-help-header"));
    sender.sendMessage(messages.component("watacoins-help-shop"));
    sender.sendMessage(messages.component("watacoins-help-balance"));
    if (!sender.isOp()) return;
    sender.sendMessage(messages.component("watacoins-help-admin-header"));
    sender.sendMessage(messages.component("watacoins-help-admin-give"));
    sender.sendMessage(messages.component("watacoins-help-admin-take"));
    sender.sendMessage(messages.component("watacoins-help-admin-set"));
    sender.sendMessage(messages.component("watacoins-help-admin-reset"));
    sender.sendMessage(messages.component("watacoins-help-admin-reload"));
    sender.sendMessage(messages.component("watacoins-help-admin-status"));
    sender.sendMessage(messages.component("watacoins-help-admin-review"));
    sender.sendMessage(messages.component("watacoins-help-admin-recovery"));
  }

  private void executeAdmin(CommandSender sender, String[] args) {
    if (!sender.isOp()) {
      messages.send(sender, "no-permission");
      return;
    }
    if (args.length < 2) {
      messages.send(sender, "watacoins-admin-usage");
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    if (action.equals("status")) {
      if (args.length != 2) messages.send(sender, "watacoins-admin-usage");
      else status(sender);
      return;
    }
    if (action.equals("review")) {
      reviewCommand(sender, args);
      return;
    }
    if (action.equals("recovery")) {
      recoveryCommand(sender, args);
      return;
    }
    if (action.equals("reload")) {
      if (args.length != 2) messages.send(sender, "watacoins-admin-usage");
      else reload(sender);
      return;
    }
    if (!ADMIN_ACTIONS.contains(action)) {
      messages.send(sender, "watacoins-admin-usage");
      return;
    }
    if (action.equals("reset")) {
      if (args.length != 3) {
        messages.send(sender, "watacoins-admin-usage");
        return;
      }
      mutate(sender, args[2], action, 0);
      return;
    }
    if (args.length != 4) {
      messages.send(sender, "watacoins-admin-usage");
      return;
    }
    Long amount = parseAmount(args[3], action.equals("set"));
    if (amount == null) {
      messages.send(sender, "invalid-amount");
      return;
    }
    mutate(sender, args[2], action, amount);
  }

  private void mutate(CommandSender sender, String target, String operation, long amount) {
    var pendingByName = wallets.pendingAdministrativeOperation(target);
    if (pendingByName.isPresent()) {
      sendAdministrativePending(sender, target, pendingByName.get().operationId(), true);
      return;
    }
    Player onlineTarget = Bukkit.getPlayerExact(target);
    if (onlineTarget != null
        && wallets.pendingAdministrativeOperation(onlineTarget.getUniqueId()).isPresent()) {
      sendAdministrativePending(sender, target,
          wallets.pendingAdministrativeOperation(onlineTarget.getUniqueId()).orElseThrow()
              .operationId(), true);
      return;
    }
    if (wallets.health() != net.watones.novacoins.storage.StorageHealth.HEALTHY
        || wallets.journalHealth() != net.watones.novacoins.storage.JournalHealth.HEALTHY) {
      messages.send(sender, "admin-storage-unavailable", Map.of("player", target));
      return;
    }
    withAccount(sender, target, (uuid, account) -> {
      String reference = sender instanceof Player player
          ? "admin:" + player.getUniqueId() : "admin:CONSOLE";
      var alreadyPending = wallets.pendingAdministrativeOperation(uuid);
      if (alreadyPending.isPresent()) {
        sendAdministrativePending(sender, account.lastName(),
            alreadyPending.get().operationId(), true);
        return;
      }
      var mutation = switch (operation) {
        case "give" -> wallets.administrativeMutation(uuid, MutationKind.CREDIT, amount,
            TransactionType.ADMIN_GIVE, "ADMIN_GIVE", reference);
        case "take" -> wallets.administrativeMutation(uuid, MutationKind.DEBIT, amount,
            TransactionType.ADMIN_TAKE, "ADMIN_TAKE", reference);
        case "set" -> wallets.administrativeMutation(uuid, MutationKind.SET, amount,
            TransactionType.ADMIN_SET, "ADMIN_SET", reference);
        case "reset" -> wallets.administrativeMutation(uuid, MutationKind.SET, 0,
            TransactionType.ADMIN_RESET, "ADMIN_RESET", reference);
        default -> throw new IllegalStateException("Unsupported admin mutation " + operation);
      };
      mutation.whenComplete((result, error) -> sync(() -> {
        if (error != null) messages.send(sender, "account-error");
        else if (result.success()) {
          messages.send(sender, "admin-" + operation + "-success", Map.of(
              "player", account.lastName(),
              "amount", Formatters.number(amount),
              "balance", Formatters.number(result.balanceAfter())));
        } else if (result.status() == EconomyResult.Status.ADMIN_PENDING) {
          sendAdministrativePending(sender, account.lastName(), result.operationId(), false);
        } else if (result.status() == EconomyResult.Status.ADMIN_ALREADY_PENDING) {
          sendAdministrativePending(sender, account.lastName(), result.operationId(), true);
        } else if (result.status() == EconomyResult.Status.INSUFFICIENT_FUNDS) {
          messages.send(sender, "insufficient-funds", Map.of("price", Formatters.number(amount)));
        } else if (result.status() == EconomyResult.Status.STORAGE_UNAVAILABLE
            || result.status() == EconomyResult.Status.JOURNAL_UNAVAILABLE) {
          messages.send(sender, "admin-storage-unavailable",
              Map.of("player", account.lastName()));
        } else {
          messages.send(sender, "account-error");
        }
        if (Bukkit.getPlayer(uuid) == null) wallets.unload(uuid);
      }));
    });
  }

  private void sendAdministrativePending(
      CommandSender sender, String player, UUID operationId, boolean alreadyPending) {
    messages.send(sender, alreadyPending ? "admin-already-pending" : "admin-pending", Map.of(
        "player", player,
        "operation", operationId.toString().substring(0, 8)));
  }

  private void reload(CommandSender sender) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> parseAndApplyReload(sender));
  }

  private void parseAndApplyReload(CommandSender sender) {
    try {
      RuntimeConfig runtimeCandidate = config.parseCandidate();
      var messageCandidate = messages.parseCandidate();
      var shopCandidate = shopConfig.validateSafety(
          shopConfig.parseCandidate(), runtimeCandidate.allowMultipleIrreversibleActions());
      boolean storageChanged =
          !runtimeCandidate.storage().equals(activeStorageSettings)
              || !runtimeCandidate.queues().equals(activeQueueLimits)
              || !runtimeCandidate.recovery().equals(activeRecoverySettings)
              || runtimeCandidate.leaderboardCacheSeconds() != activeLeaderboardCacheSeconds;
      sync(() -> {
        config.swap(storageChanged
            ? runtimeCandidate.withNonReloadable(activeStorageSettings, activeQueueLimits,
                activeLeaderboardCacheSeconds, activeRecoverySettings)
            : runtimeCandidate);
        wallets.configureShutdownTimeouts(runtimeCandidate.journalDrainTimeoutSeconds(),
            runtimeCandidate.databaseDrainTimeoutSeconds());
        wallets.onDebug(runtimeCandidate.debug()
            ? message -> plugin.getLogger().info("[debug] " + message) : null);
        messages.swap(messageCandidate);
        shopConfig.swap(shopCandidate);
        alertReload.accept(runtimeCandidate.alerts());
        messages.send(sender, "reload-success");
        if (storageChanged) messages.send(sender, "storage-restart");
        plugin.getLogger().info("Canjes recargados: " + shop.rewardCount());
      });
    } catch (Exception failure) {
      sync(() -> messages.send(sender, "reload-failed", Map.of(
          "error", failure.getMessage() == null
              ? failure.getClass().getSimpleName() : failure.getMessage())));
      plugin.getLogger().warning("Reload rechazado: " + failure.getMessage());
    }
  }

  private void withAccount(
      CommandSender sender, String name, BiConsumer<UUID, PlayerAccount> action) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      wallets.load(online.getUniqueId(), online.getName()).whenComplete((account, error) ->
          sync(() -> {
            if (error != null) messages.send(sender, "account-error");
            else action.accept(online.getUniqueId(), account);
          }));
      return;
    }
    wallets.findUuid(name).whenComplete((found, error) -> {
      if (error != null) {
        sync(() -> messages.send(sender, "account-error"));
        return;
      }
      if (found.isEmpty()) {
        sync(() -> messages.send(sender, "player-not-found", Map.of("player", name)));
        return;
      }
      UUID uuid = found.get();
      wallets.load(uuid, name).whenComplete((account, loadError) -> sync(() -> {
        if (loadError != null) messages.send(sender, "account-error");
        else {
          action.accept(uuid, account);
          wallets.unload(uuid);
        }
      }));
    });
  }

  private void sync(Runnable task) {
    Bukkit.getScheduler().runTask(plugin, task);
  }

  private Long parseAmount(String raw, boolean allowZero) {
    try {
      long value = Long.parseLong(raw);
      return value > 0 || (allowZero && value == 0) ? value : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> visible = sender.isOp()
          ? List.of("balance", "help", "admin") : List.of("balance", "help");
      return matching(visible, args[0]);
    }
    if (!sender.isOp() || !args[0].equalsIgnoreCase("admin")) return List.of();
    if (args.length == 2) return matching(ADMIN_ACTIONS, args[1]);
    if (args.length == 3 && args[1].equalsIgnoreCase("recovery")) {
      return matching(List.of("corrupt"), args[2]);
    }
    if (args.length == 4 && args[1].equalsIgnoreCase("review")) {
      return matching(List.of("delivered", "refund", "retry"), args[3]);
    }
    if (args.length == 5 && args[1].equalsIgnoreCase("review")) {
      return matching(List.of("confirm"), args[4]);
    }
    if (args.length == 3 && List.of("give", "take", "set", "reset")
        .contains(args[1].toLowerCase(Locale.ROOT))) {
      return Bukkit.getOnlinePlayers().stream().map(Player::getName)
          .filter(name -> name.toLowerCase(Locale.ROOT)
              .startsWith(args[2].toLowerCase(Locale.ROOT)))
          .toList();
    }
    return List.of();
  }

  private List<String> matching(List<String> values, String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return values.stream().filter(value -> value.startsWith(normalized)).toList();
  }

  private void status(CommandSender sender) {
    WalletService.Metrics metrics = wallets.metrics();
    wallets.operationalStatus().whenComplete((status, error) -> sync(() -> {
      long reviews = status == null ? metrics.manualReviewCount() : status.manualReviews();
      int purchases = status == null ? metrics.pendingDeliveries() : status.pendingPurchases();
      sender.sendMessage(messages.component("admin-status-header"));
      sender.sendMessage(messages.component("admin-status-storage", Map.of(
          "health", wallets.health().name(), "storage", storageDescription)));
      sender.sendMessage(messages.component("admin-status-journal", Map.of(
          "health", wallets.journalHealth().name(),
          "queue", metrics.journalQueueSize() + "/" + metrics.journalQueueCapacity(),
          "pending", Integer.toString(metrics.recoveryPending()))));
      sender.sendMessage(messages.component("admin-status-economy", Map.of(
          "mutations", Integer.toString(metrics.pendingMutations()),
          "purchases", Integer.toString(purchases),
          "reviews", Long.toString(reviews))));
      sender.sendMessage(messages.component("admin-status-sessions", Map.of(
          "sessions", Integer.toString(sessions.registry().size()),
          "completed", Integer.toString(sessions.pendingCompletedRewards()),
          "capacity", Integer.toString(sessions.pendingCompletedCapacity()),
          "backpressure", sessions.rewardBackpressure() ? "ON" : "OFF")));
      sender.sendMessage(messages.component("admin-status-webhook", Map.of(
          "state", alerts != null && alerts.enabled() ? "ON" : "OFF",
          "queue", alerts == null ? "0/0" : alerts.queueSize() + "/" + alerts.queueCapacity())));
      if (error != null) messages.send(sender, "admin-status-partial");
    }));
  }

  private void recoveryCommand(CommandSender sender, String[] args) {
    if (args.length == 3 && args[2].equalsIgnoreCase("corrupt")) {
      wallets.corruptRecoveryRecords().whenComplete((records, error) -> sync(() -> {
        if (error != null) {
          messages.send(sender, "account-error");
          return;
        }
        messages.send(sender, "recovery-corrupt-header",
            Map.of("count", Integer.toString(records.size())));
        for (var record : records) messages.send(sender, "recovery-corrupt-entry", Map.of(
            "file", record.filename(), "date", DATE.format(record.detectedAt()),
            "error", record.error()));
      }));
      return;
    }
    if (args.length != 2) {
      messages.send(sender, "watacoins-admin-usage");
      return;
    }
    wallets.replayRecovery().whenComplete((report, error) -> sync(() -> {
      if (error != null) messages.send(sender, "recovery-run-failed");
      else messages.send(sender, "recovery-run-result", Map.of(
          "inspected", Integer.toString(report.recovered()),
          "progress", Integer.toString(report.actualProgress()),
          "pending", Integer.toString(report.stillPending())));
    }));
  }

  private void reviewCommand(CommandSender sender, String[] args) {
    if (args.length == 2) {
      wallets.deliveryFailures(1, 50).whenComplete((entries, error) -> sync(() -> {
        if (error != null) {
          messages.send(sender, "account-error");
          return;
        }
        List<CoinTransaction> reviews = entries.stream()
            .filter(transaction -> transaction.status() == TransactionStatus.MANUAL_REVIEW)
            .toList();
        messages.send(sender, "review-list-header", Map.of("count", Integer.toString(reviews.size())));
        for (CoinTransaction transaction : reviews) messages.send(sender, "review-list-entry", Map.of(
            "operation", transaction.operationId().toString(),
            "player", transaction.uuid().toString(),
            "reward", transaction.reference()));
      }));
      return;
    }
    UUID operationId = operationId(sender, args[2]);
    if (operationId == null) return;
    if (args.length == 3) {
      wallets.transaction(operationId).whenComplete((found, error) -> sync(() -> {
        if (error != null) messages.send(sender, "account-error");
        else if (found.isEmpty()) messages.send(sender, "review-not-found");
        else {
          CoinTransaction transaction = found.orElseThrow();
          messages.send(sender, "review-detail", Map.of(
              "operation", transaction.operationId().toString(),
              "player", transaction.uuid().toString(),
              "reward", transaction.reference(),
              "status", transaction.status().name(),
              "created", DATE.format(transaction.createdAt()),
              "reason", transaction.deliveryError() == null
                  ? "sin detalle" : transaction.deliveryError()));
        }
      }));
      return;
    }
    if (args.length < 4 || !Set.of("delivered", "refund", "retry")
        .contains(args[3].toLowerCase(Locale.ROOT))) {
      messages.send(sender, "review-usage");
      return;
    }
    if (args.length > 5 || (args.length == 5 && !args[4].equalsIgnoreCase("confirm"))) {
      messages.send(sender, "review-usage");
      return;
    }
    resolveReview(sender, operationId, args[3].toLowerCase(Locale.ROOT),
        args.length == 5 && args[4].equalsIgnoreCase("confirm"));
  }

  private UUID operationId(CommandSender sender, String raw) {
    try { return UUID.fromString(raw); }
    catch (IllegalArgumentException invalid) {
      messages.send(sender, "retry-invalid");
      return null;
    }
  }

  private void resolveReview(
      CommandSender sender, UUID operationId, String action, boolean confirmed) {
    String admin = sender instanceof Player player
        ? player.getUniqueId().toString() : "CONSOLE";
    ConfirmationKey key = new ConfirmationKey(admin, operationId, action);
    long now = System.nanoTime();
    confirmations.entrySet().removeIf(entry -> now - entry.getValue() > CONFIRMATION_NANOS);
    if (!confirmed) {
      confirmations.put(key, now);
      messages.send(sender, "review-confirm", Map.of(
          "operation", operationId.toString(), "action", action));
      return;
    }
    Long issued = confirmations.remove(key);
    if (issued == null || now - issued > CONFIRMATION_NANOS) {
      messages.send(sender, "review-confirm-expired");
      return;
    }
    switch (action) {
      case "delivered" -> wallets.resolveReviewDelivered(operationId, admin)
          .whenComplete((ignored, error) -> reviewResolved(sender, operationId, action, error));
      case "refund" -> wallets.resolveReviewRefund(operationId, admin)
          .whenComplete((ignored, error) -> reviewResolved(sender, operationId, action, error));
      case "retry" -> wallets.resolveReviewRetry(operationId, admin)
          .whenComplete((result, error) -> {
            if (error == null && result != null && result.success()) {
              sync(() -> shop.retryDelivery(operationId));
            }
            reviewResolved(sender, operationId, action, error);
          });
      default -> throw new IllegalStateException();
    }
  }

  private void reviewResolved(
      CommandSender sender, UUID operationId, String action, Throwable error) {
    sync(() -> {
      if (error != null) messages.send(sender, "review-resolution-failed");
      else {
        messages.send(sender, "review-resolution-success", Map.of(
            "operation", operationId.toString(), "action", action));
        if (alerts != null) alerts.alert("manual-review-resolved:" + operationId,
            "MANUAL_REVIEW resuelta", "Operación: " + operationId + "\nAcción: " + action);
      }
    });
  }

  private record ConfirmationKey(String admin, UUID operationId, String action) {}
}
