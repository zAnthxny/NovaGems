package net.watones.novagems;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.watones.novagems.alert.DiscordWebhookAlertService;
import net.watones.novagems.activity.ActivityGuard;
import net.watones.novagems.activity.ConservativeActivityGuard;
import net.watones.novagems.command.NovaGemsCommand;
import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.config.RuntimeConfig;
import net.watones.novagems.config.ShopConfig;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.listener.ActivityListener;
import net.watones.novagems.listener.KillRewardListener;
import net.watones.novagems.listener.PlayerConnectionListener;
import net.watones.novagems.message.MessageService;
import net.watones.novagems.placeholder.NovaGemsExpansion;
import net.watones.novagems.session.DailyKillTracker;
import net.watones.novagems.session.RewardService;
import net.watones.novagems.session.SessionRegistry;
import net.watones.novagems.session.SessionService;
import net.watones.novagems.shop.ShopService;
import net.watones.novagems.shop.listener.ShopListener;
import net.watones.novagems.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class NovaGemsPlugin extends JavaPlugin {
  private volatile StorageProvider storage;
  private volatile WalletService wallets;
  private volatile SessionService sessions;
  private volatile BukkitTask rewardTask;
  private volatile BukkitTask alertTask;
  private volatile DiscordWebhookAlertService alerts;
  private volatile boolean stopping;
  private volatile int shutdownTotalSeconds = 10;

  @Override
  public void onEnable() {
    // File creation, YAML reads, migrations and connection acquisition are deliberately kept off
    // Paper's primary thread. Bukkit registration is resumed on the primary thread afterwards.
    Thread.ofPlatform().name("NovaGems-Bootstrap").start(this::bootstrapOffThread);
  }

  private void bootstrapOffThread() {
    try {
      saveDefaultConfig();
      saveBundled("messages.yml");
      saveBundled("shop.yml");

      ConfigManager config = new ConfigManager(this);
      RuntimeConfig runtime = config.load();
      alerts = new DiscordWebhookAlertService(
          runtime.alerts(), message -> getLogger().warning("[Discord] " + message));
      MessageService messages = new MessageService(this);
      ShopConfig shopConfig = new ShopConfig(this);
      var initialShop = shopConfig.validateSafety(
          shopConfig.parseCandidate(), runtime.allowMultipleIrreversibleActions());
      shopConfig.swap(initialShop);

      StorageProvider initializedStorage = config.createStorage();
      initializedStorage.initialize();
      initializedStorage.configureMaxBalance(runtime.maxBalance());
      RuntimeConfig.QueueLimits queueLimits = runtime.queues();
      RuntimeConfig.RecoverySettings recovery = runtime.recovery();
      WalletService initializedWallets = new WalletService(
          initializedStorage,
          getDataFolder().toPath().resolve("recovery"),
          error -> {
            getLogger().log(Level.SEVERE, "Fallo persistiendo datos económicos", error);
            DiscordWebhookAlertService currentAlerts = alerts;
            if (currentAlerts != null) currentAlerts.alert("storage-persistence",
                "Fallo de persistencia", errorSummary(error));
          },
          queueLimits.executorCapacity(),
          queueLimits.perPlayer(),
          queueLimits.global(),
          runtime.leaderboardCacheSeconds(),
          runtime.databaseDrainTimeoutSeconds(),
          recovery.writerQueueCapacity(),
          recovery.batchSize(),
          recovery.baseIntervalSeconds(),
          recovery.maxBackoffSeconds());
      initializedWallets.configureShutdownTimeouts(
          runtime.journalDrainTimeoutSeconds(), runtime.databaseDrainTimeoutSeconds());
      initializedWallets.onAdministrativeLog(message -> getLogger().warning(message));
      initializedWallets.onManualReviewCreated(operationId -> {
        DiscordWebhookAlertService currentAlerts = alerts;
        if (currentAlerts != null) currentAlerts.alert("manual-review:" + operationId,
            "Operación en MANUAL_REVIEW",
            "Operación: " + operationId + "\nRequiere revisión de un operador.");
      });
      if (runtime.debug()) {
        initializedWallets.onDebug(message -> getLogger().info("[debug] " + message));
      }

      SessionRegistry registry = new SessionRegistry();
      ActivityGuard guard = new ConservativeActivityGuard(config);
      RewardService rewards = new RewardService(this, initializedWallets, config, messages);
      SessionService initializedSessions = new SessionService(
          registry, guard, rewards, config, this, messages, recovery.writerQueueCapacity());
      ShopService shop = new ShopService(
          this, shopConfig, config, initializedWallets, messages, initializedSessions);
      BootstrapContext context = new BootstrapContext(config, runtime, messages, shopConfig,
          initializedStorage, initializedWallets, registry, guard, initializedSessions, shop);
      Bukkit.getScheduler().runTask(this, () -> finishEnable(context));
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE, "NovaGems no puede iniciar de forma segura", failure);
      DiscordWebhookAlertService currentAlerts = alerts;
      if (currentAlerts != null) currentAlerts.alert("startup-failure", "Fallo de inicio",
          errorSummary(failure));
      Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
    }
  }

  private void finishEnable(BootstrapContext context) {
    if (stopping || !isEnabled()) {
      closeContext(context);
      return;
    }
    storage = context.storage;
    wallets = context.wallets;
    sessions = context.sessions;
    shutdownTotalSeconds = Math.min(10,
        context.runtime.journalDrainTimeoutSeconds()
            + context.runtime.databaseDrainTimeoutSeconds());

    context.wallets.replayRecovery().thenAccept(report -> {
      if (report.recovered() > 0 || report.stillPending() > 0) {
        getLogger().info("Recuperación local: " + report.recovered()
            + " operaciones conciliadas, " + report.stillPending() + " pendientes");
      }
    });
    PlayerConnectionListener connections = new PlayerConnectionListener(
        this, context.wallets, context.sessions, context.shop);
    getServer().getPluginManager().registerEvents(connections, this);
    getServer().getPluginManager().registerEvents(new ActivityListener(context.guard), this);
    getServer().getPluginManager().registerEvents(new ShopListener(context.shop), this);
    DailyKillTracker killTracker = new DailyKillTracker();
    getServer().getPluginManager().registerEvents(
        new KillRewardListener(context.wallets, context.config, killTracker), this);
    restoreDailyKills(context.wallets, killTracker);

    NovaGemsCommand novaGems = new NovaGemsCommand(
        this, context.wallets, context.messages, context.config, context.shopConfig,
        context.shop, context.runtime.storage(),
        context.runtime.queues(), context.runtime.leaderboardCacheSeconds(),
        context.runtime.recovery(), context.sessions, context.storage.description(), alerts,
        next -> {
          DiscordWebhookAlertService currentAlerts = alerts;
          if (currentAlerts != null) currentAlerts.reconfigure(next);
        });
    command("novagems").setExecutor(novaGems);
    command("novagems").setTabCompleter(novaGems);
    command("gemas").setExecutor(novaGems);
    command("gemas").setTabCompleter(novaGems);

    rewardTask = Bukkit.getScheduler().runTaskTimer(this, context.sessions::tick, 20L, 20L);
    alertTask = Bukkit.getScheduler().runTaskTimer(this, this::checkOperationalAlerts,
        20L * 60L, 20L * 60L);
    context.wallets.corruptRecoveryRecords().thenAccept(records -> {
      if (!records.isEmpty() && alerts != null) alerts.alert("journal-corrupt",
          "Recovery journal corrupto",
          records.size() + " registro(s) en cuarentena. Usa /novagems admin recovery corrupt.");
    });
    context.wallets.operationalStatus().thenAccept(status -> {
      if (status.manualReviews() > 0 && alerts != null) alerts.alert("manual-reviews-existing",
          "Operaciones pendientes de revisión",
          status.manualReviews() + " operación(es) permanecen en MANUAL_REVIEW.");
    });
    Bukkit.getOnlinePlayers().forEach(connections::start);
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      new NovaGemsExpansion(context.wallets, context.registry, context.config).register();
      getLogger().info("Integración con PlaceholderAPI habilitada");
    }
    getLogger().info("Storage: " + context.storage.description());
    getLogger().info("Canjes cargados: " + context.shop.rewardCount());
    if (context.runtime.debug()) {
      getLogger().info("Debug activo: workers=" + context.storage.workerThreads()
          + ", cola=" + context.runtime.queues().executorCapacity()
          + ", límite global=" + context.runtime.queues().global());
    }
    getLogger().info("NovaGems v" + getPluginMeta().getVersion() + " habilitado");
  }

  @Override
  public void onDisable() {
    stopping = true;
    if (rewardTask != null) rewardTask.cancel();
    if (alertTask != null) alertTask.cancel();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownTotalSeconds);
    SessionService.ShutdownReport sessionReport = sessions == null
        ? new SessionService.ShutdownReport(0, 0) : sessions.shutdownAndDrain(deadline);
    WalletService.ShutdownReport walletReport = wallets == null
        ? new WalletService.ShutdownReport(0, 0, true) : wallets.closeUntil(deadline);
    if (sessionReport.completedRewardsNotDurable() > 0 || !walletReport.clean()) {
      String detail = walletReport.journalWritesPending() + " journal writes pending, "
          + walletReport.databaseMutationsPending() + " DB mutations pending, "
          + sessionReport.completedRewardsNotDurable() + " completed rewards not durable";
      getLogger().severe(detail);
      if (alerts != null) alerts.alert("shutdown-incomplete", "Apagado incompleto", detail);
    }
    if (storage != null) {
      try { storage.close(); }
      catch (Exception failure) {
        getLogger().log(Level.SEVERE, "Error cerrando storage", failure);
        if (alerts != null) alerts.alert("storage-close", "Error cerrando storage",
            errorSummary(failure));
      }
    }
    if (alerts != null) alerts.close();
  }

  private void closeContext(BootstrapContext context) {
    context.wallets.close();
    try { context.storage.close(); }
    catch (Exception failure) { getLogger().log(Level.SEVERE, "Error cerrando bootstrap", failure); }
    if (alerts != null) alerts.close();
  }

  /**
   * Daily kill limits live in memory while the server runs, so a mid-day restart would otherwise
   * hand every player a fresh allowance. Re-read today's rows and drop everything older.
   */
  private void restoreDailyKills(WalletService wallets, DailyKillTracker tracker) {
    String today = tracker.todayKey();
    wallets
        .loadDailyKills(today)
        .whenComplete(
            (persisted, error) -> {
              if (error != null) {
                getLogger().log(Level.WARNING,
                    "No se pudieron restaurar los límites diarios de asesinatos; este día arranca"
                        + " en cero", error);
                return;
              }
              tracker.seed(today, persisted);
              if (!persisted.isEmpty()) {
                getLogger().info("Límites diarios de asesinatos restaurados para "
                    + persisted.size() + " jugador(es)");
              }
            });
    wallets
        .pruneDailyKillsBefore(today)
        .exceptionally(
            error -> {
              getLogger().log(Level.WARNING, "No se pudieron purgar asesinatos de días previos",
                  error);
              return 0;
            });
  }

  private void checkOperationalAlerts() {
    if (alerts == null || wallets == null || sessions == null) return;
    if (wallets.health() != net.watones.novagems.storage.StorageHealth.HEALTHY) {
      alerts.alert("storage-health", "Storage no saludable",
          "Estado actual: " + wallets.health());
    }
    if (wallets.journalHealth() != net.watones.novagems.storage.JournalHealth.HEALTHY) {
      alerts.alert("journal-health", "Journal no saludable",
          "Estado actual: " + wallets.journalHealth());
    }
    if (sessions.rewardBackpressure()) {
      alerts.alert("reward-backpressure", "Reward backpressure activo",
          "Completed rewards: " + sessions.pendingCompletedRewards() + "/"
              + sessions.pendingCompletedCapacity());
    }
  }

  private String errorSummary(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
    message = message.replace('\n', ' ').replace('\r', ' ');
    if (message.length() > 400) message = message.substring(0, 400) + "…";
    return failure.getClass().getSimpleName() + ": " + message;
  }

  private PluginCommand command(String name) {
    return Objects.requireNonNull(getCommand(name), "Comando ausente en plugin.yml: " + name);
  }

  private void saveBundled(String name) {
    if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
  }

  private record BootstrapContext(
      ConfigManager config,
      RuntimeConfig runtime,
      MessageService messages,
      ShopConfig shopConfig,
      StorageProvider storage,
      WalletService wallets,
      SessionRegistry registry,
      ActivityGuard guard,
      SessionService sessions,
      ShopService shop) {}
}
