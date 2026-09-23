package net.watones.novagems.config;

import org.bukkit.Sound;

public record RuntimeConfig(
    long intervalSeconds,
    long gemsPerInterval,
    long maxBalance,
    KillRewards killRewards,
    StorageSettings storage,
    FullInventoryBehavior fullInventoryBehavior,
    ShopSounds shopSounds,
    Notification notification,
    AntiAbuse antiAbuse,
    QueueLimits queues,
    boolean allowMultipleIrreversibleActions,
    int leaderboardCacheSeconds,
    int shutdownDrainTimeoutSeconds,
    RecoverySettings recovery,
    int journalDrainTimeoutSeconds,
    int databaseDrainTimeoutSeconds,
    AlertSettings alerts,
    BackupSettings backup,
    boolean debug) {
  public String storageType() {
    return storage.type();
  }

  public RuntimeConfig withNonReloadable(
      StorageSettings activeStorage, QueueLimits activeQueues, int activeLeaderboardCacheSeconds,
      RecoverySettings activeRecovery) {
    return new RuntimeConfig(
        intervalSeconds,
        gemsPerInterval,
        maxBalance,
        killRewards,
        activeStorage,
        fullInventoryBehavior,
        shopSounds,
        notification,
        antiAbuse,
        activeQueues,
        allowMultipleIrreversibleActions,
        activeLeaderboardCacheSeconds,
        shutdownDrainTimeoutSeconds,
        activeRecovery,
        journalDrainTimeoutSeconds,
        databaseDrainTimeoutSeconds,
        alerts,
        backup,
        debug);
  }

  public enum FullInventoryBehavior {
    DENY,
    DROP
  }

  public record StorageSettings(
      String type,
      String sqliteFile,
      String host,
      int port,
      String database,
      String username,
      String password,
      int poolSize,
      boolean ssl) {}

  public record QueueLimits(int executorCapacity, int perPlayer, int global) {}

  public record RecoverySettings(
      int writerQueueCapacity,
      int batchSize,
      int baseIntervalSeconds,
      int maxBackoffSeconds) {}

  public record AlertSettings(
      boolean enabled, String webhookUrl, int timeoutMillis, int dedupeSeconds) {
    @Override public String toString() {
      return "AlertSettings[enabled=" + enabled + ",configured=" + !webhookUrl.isBlank()
          + ",timeoutMillis=" + timeoutMillis + ",dedupeSeconds=" + dedupeSeconds + "]";
    }
  }

  public record ShopSounds(
      boolean enabled, Sound click, Sound success, Sound failure, float volume, float pitch) {}

  public record Notification(
      boolean chat, boolean actionbar, boolean sound, Sound soundName, float volume, float pitch) {}

  public record AntiAbuse(
      boolean enabled,
      long minimumObservationSeconds,
      int minimumSamples,
      double threshold,
      long evaluationIntervalSeconds,
      boolean notifyPlayer,
      boolean notifyConsole) {}

  public record KillRewards(boolean enabled, long gemsPerKill, int dailyLimit) {}

  /** Daily SQLite snapshot into plugins/NovaGems/backups/, keeping the newest {@code keepDays}. */
  public record BackupSettings(boolean enabled, int keepDays) {}
}
