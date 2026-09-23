package net.watones.novagems.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URI;
import java.util.Locale;
import net.watones.novagems.storage.MySqlStorageProvider;
import net.watones.novagems.storage.SQLiteStorageProvider;
import net.watones.novagems.storage.StorageProvider;
import net.watones.novagems.util.SoundResolver;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigManager {
  private final JavaPlugin plugin;
  private volatile RuntimeConfig current;

  public ConfigManager(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public RuntimeConfig load() {
    RuntimeConfig candidate = parseCandidate();
    swap(candidate);
    return candidate;
  }

  public RuntimeConfig parseCandidate() {
    FileConfiguration config = loadConfigFile();
    long interval = config.getLong("rewards.interval-seconds", 600);
    long gems = config.getLong("rewards.gems-per-interval", 10);
    if (interval <= 0 || interval > 31_536_000) {
      throw new IllegalArgumentException(
          "rewards.interval-seconds debe estar entre 1 y 31536000");
    }
    if (gems <= 0 || gems > 1_000_000_000_000L) {
      throw new IllegalArgumentException(
          "rewards.gems-per-interval debe estar entre 1 y 1000000000000");
    }
    long maxBalance = config.getLong("economy.max-balance", 10_000_000L);
    if (maxBalance < 1 || maxBalance > 1_000_000_000_000L) {
      throw new IllegalArgumentException("economy.max-balance debe estar entre 1 y 1000000000000");
    }
    boolean killRewardsEnabled = config.getBoolean("rewards.kills.enabled", true);
    long gemsPerKill = config.getLong("rewards.kills.gems-per-kill", 10);
    int killDailyLimit = config.getInt("rewards.kills.daily-limit", 10);
    if (gemsPerKill < 1 || gemsPerKill > maxBalance) {
      throw new IllegalArgumentException("rewards.kills.gems-per-kill debe ser positivo");
    }
    if (killDailyLimit < 0 || killDailyLimit > 100_000) {
      throw new IllegalArgumentException("rewards.kills.daily-limit debe estar entre 0 y 100000");
    }
    // Defaults on: an existing config.yml without this section still gets daily backups.
    boolean backupEnabled = config.getBoolean("backup.enabled", true);
    int backupKeepDays = config.getInt("backup.keep-days", 7);
    if (backupKeepDays < 1 || backupKeepDays > 365) {
      throw new IllegalArgumentException("backup.keep-days debe estar entre 1 y 365");
    }

    RuntimeConfig.StorageSettings storage = parseStorage(config);
    RuntimeConfig.FullInventoryBehavior inventoryBehavior;
    try {
      inventoryBehavior =
          RuntimeConfig.FullInventoryBehavior.valueOf(
              config
                  .getString("shop.full-inventory-behavior", "DENY")
                  .toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "shop.full-inventory-behavior debe ser DENY o DROP", exception);
    }

    String soundPath =
        config.contains("notifications.reward.sound.name")
            ? "notifications.reward.sound.name"
            : "notifications.sound-name";
    Sound sound =
        SoundResolver.resolve(
            config.getString(soundPath, "entity.experience_orb.pickup"));
    if (sound == null) throw new IllegalArgumentException(soundPath + " no es válido");
    double volume =
        config.getDouble(
            config.contains("notifications.reward.sound.volume")
                ? "notifications.reward.sound.volume"
                : "notifications.sound-volume",
            0.8);
    double pitch =
        config.getDouble(
            config.contains("notifications.reward.sound.pitch")
                ? "notifications.reward.sound.pitch"
                : "notifications.sound-pitch",
            1.15);
    if (!Double.isFinite(volume)
        || !Double.isFinite(pitch)
        || volume < 0
        || pitch < 0
        || pitch > 2) {
      throw new IllegalArgumentException("Volumen o pitch de notificación inválido");
    }

    String mode = config.getString("anti-abuse.mode", "CONSERVATIVE").toUpperCase(Locale.ROOT);
    if (!mode.equals("CONSERVATIVE")) {
      throw new IllegalArgumentException("anti-abuse.mode sólo admite CONSERVATIVE");
    }
    long observation = config.getLong("anti-abuse.detection.minimum-observation-seconds", 600);
    int samples = config.getInt("anti-abuse.detection.minimum-samples", 120);
    double threshold =
        config.getDouble("anti-abuse.detection.repetitive-pattern-threshold", 0.97);
    long evaluation = config.getLong("anti-abuse.detection.evaluation-interval-seconds", 10);
    if (observation < 60
        || samples < 20
        || threshold < 0.8
        || threshold > 1
        || evaluation < 5
        || evaluation > 60) {
      throw new IllegalArgumentException("Configuración anti-abuse fuera de rangos seguros");
    }

    int executorCapacity = config.getInt("storage.queues.executor-capacity", 1024);
    int perPlayer = config.getInt("storage.queues.per-player", 64);
    int global = config.getInt("storage.queues.global", 4096);
    if (executorCapacity < 32
        || executorCapacity > 100_000
        || perPlayer < 2
        || perPlayer > 1024
        || global < perPlayer
        || global > 1_000_000) {
      throw new IllegalArgumentException("Límites de colas de storage inválidos");
    }
    int leaderboardCache = config.getInt("leaderboard.cache-seconds", 30);
    if (leaderboardCache < 1 || leaderboardCache > 3600) {
      throw new IllegalArgumentException("leaderboard.cache-seconds debe estar entre 1 y 3600");
    }
    int shutdownDrain = config.getInt("shutdown.drain-timeout-seconds", 5);
    if (shutdownDrain < 1 || shutdownDrain > 30) {
      throw new IllegalArgumentException("shutdown.drain-timeout-seconds debe estar entre 1 y 30");
    }
    int journalQueue = config.getInt("recovery.writer.queue-capacity", 4096);
    int recoveryBatch = config.getInt("recovery.processing.batch-size", 250);
    int recoveryBase = config.getInt("recovery.processing.base-interval-seconds", 30);
    int recoveryMax = config.getInt("recovery.processing.max-backoff-seconds", 300);
    if (journalQueue < 64 || journalQueue > 100_000
        || recoveryBatch < 10 || recoveryBatch > 5_000
        || recoveryBase < 1 || recoveryBase > 3600
        || recoveryMax < recoveryBase || recoveryMax > 86_400) {
      throw new IllegalArgumentException("Configuración de recovery fuera de rangos seguros");
    }
    int journalDrain = config.getInt("shutdown.journal-drain-timeout-seconds", shutdownDrain);
    int databaseDrain = config.getInt("shutdown.database-drain-timeout-seconds", shutdownDrain);
    if (journalDrain < 1 || journalDrain > 30 || databaseDrain < 1 || databaseDrain > 30) {
      throw new IllegalArgumentException("Timeouts de shutdown deben estar entre 1 y 30");
    }
    RuntimeConfig.AlertSettings alerts = parseAlerts(config);

    Sound shopClick =
        parseSound(config, "shop.sounds.click", "ui.button.click");
    Sound shopSuccess =
        parseSound(config, "shop.sounds.success", "entity.player.levelup");
    Sound shopFailure =
        parseSound(config, "shop.sounds.failure", "block.note_block.bass");
    double shopVolume = config.getDouble("shop.sounds.volume", 0.6);
    double shopPitch = config.getDouble("shop.sounds.pitch", 1.0);
    if (!Double.isFinite(shopVolume)
        || !Double.isFinite(shopPitch)
        || shopVolume < 0
        || shopPitch < 0
        || shopPitch > 2) {
      throw new IllegalArgumentException("Volumen o pitch de sonidos de tienda inválido");
    }

    String rewardPrefix = "notifications.reward.";
    return new RuntimeConfig(
        interval,
        gems,
        maxBalance,
        new RuntimeConfig.KillRewards(killRewardsEnabled, gemsPerKill, killDailyLimit),
        storage,
        inventoryBehavior,
        new RuntimeConfig.ShopSounds(
            config.getBoolean("shop.sounds.enabled", true),
            shopClick,
            shopSuccess,
            shopFailure,
            (float) shopVolume,
            (float) shopPitch),
        new RuntimeConfig.Notification(
            config.getBoolean(
                config.contains(rewardPrefix + "chat")
                    ? rewardPrefix + "chat"
                    : "notifications.chat",
                true),
            config.getBoolean(
                config.contains(rewardPrefix + "actionbar")
                    ? rewardPrefix + "actionbar"
                    : "notifications.actionbar",
                false),
            config.getBoolean(
                config.contains(rewardPrefix + "sound.enabled")
                    ? rewardPrefix + "sound.enabled"
                    : "notifications.sound",
                true),
            sound,
            (float) volume,
            (float) pitch),
        new RuntimeConfig.AntiAbuse(
            config.getBoolean("anti-abuse.enabled", true),
            observation,
            samples,
            threshold,
            evaluation,
            config.getBoolean("anti-abuse.notifications.player", false),
            config.getBoolean("anti-abuse.notifications.console", false)),
        new RuntimeConfig.QueueLimits(executorCapacity, perPlayer, global),
        config.getBoolean("shop.allow-multiple-irreversible-actions", false),
        leaderboardCache,
        shutdownDrain,
        new RuntimeConfig.RecoverySettings(
            journalQueue, recoveryBatch, recoveryBase, recoveryMax),
        journalDrain,
        databaseDrain,
        alerts,
        new RuntimeConfig.BackupSettings(backupEnabled, backupKeepDays),
        config.getBoolean("debug", false));
  }

  public void swap(RuntimeConfig candidate) {
    current = candidate;
  }

  public RuntimeConfig current() {
    return current;
  }

  public StorageProvider createStorage() {
    RuntimeConfig.StorageSettings settings = current.storage();
    if (settings.type().equals("SQLITE")) {
      return new SQLiteStorageProvider(
          plugin.getDataFolder().toPath().resolve(settings.sqliteFile()));
    }
    return new MySqlStorageProvider(
        settings.host(),
        settings.port(),
        settings.database(),
        settings.username(),
        settings.password(),
        settings.poolSize(),
        settings.ssl());
  }

  private RuntimeConfig.StorageSettings parseStorage(FileConfiguration config) {
    String type = config.getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT);
    if (!type.equals("SQLITE") && !type.equals("MYSQL") && !type.equals("MARIADB")) {
      throw new IllegalArgumentException("storage.type debe ser SQLITE, MYSQL o MARIADB");
    }
    String sqliteFile = config.getString("storage.sqlite.file", "novagems.db");
    if (sqliteFile.isBlank() || sqliteFile.contains("..") || Path.of(sqliteFile).isAbsolute()) {
      throw new IllegalArgumentException("storage.sqlite.file debe ser un nombre relativo seguro");
    }
    String host = config.getString("storage.mysql.host", "localhost");
    String database = config.getString("storage.mysql.database", "novagems");
    String username = config.getString("storage.mysql.username", "root");
    if (!type.equals("SQLITE")) {
      host = required(config, "storage.mysql.host");
      database = required(config, "storage.mysql.database");
      username = required(config, "storage.mysql.username");
    }
    int port = config.getInt("storage.mysql.port", 3306);
    int pool = config.getInt("storage.mysql.pool-size", 10);
    if (port < 1 || port > 65535 || pool < 1 || pool > 50) {
      throw new IllegalArgumentException("Puerto o pool MySQL inválido");
    }
    return new RuntimeConfig.StorageSettings(
        type,
        sqliteFile,
        host,
        port,
        database,
        username,
        config.getString("storage.mysql.password", ""),
        pool,
        config.getBoolean("storage.mysql.use-ssl", false));
  }

  private RuntimeConfig.AlertSettings parseAlerts(FileConfiguration config) {
    boolean enabled = config.getBoolean("alerts.discord.enabled", false);
    String url = config.getString("alerts.discord.webhook-url", "").trim();
    int timeout = config.getInt("alerts.discord.timeout-millis", 3000);
    int dedupe = config.getInt("alerts.discord.dedupe-seconds", 300);
    if (timeout < 500 || timeout > 10_000 || dedupe < 10 || dedupe > 86_400) {
      throw new IllegalArgumentException("Configuración de alerts.discord fuera de rango");
    }
    if (enabled) {
      URI parsed;
      try { parsed = URI.create(url); }
      catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("alerts.discord.webhook-url no es una URL válida");
      }
      if (!"https".equalsIgnoreCase(parsed.getScheme())
          || !"discord.com".equalsIgnoreCase(parsed.getHost())
          || parsed.getPath() == null
          || !parsed.getPath().startsWith("/api/webhooks/")) {
        throw new IllegalArgumentException(
            "alerts.discord.webhook-url debe ser una webhook HTTPS de discord.com");
      }
    }
    return new RuntimeConfig.AlertSettings(enabled, url, timeout, dedupe);
  }

  private Sound parseSound(FileConfiguration config, String path, String fallback) {
    Sound sound = SoundResolver.resolve(config.getString(path, fallback));
    if (sound == null) throw new IllegalArgumentException(path + " no es válido");
    return sound;
  }

  private String required(FileConfiguration config, String path) {
    String value = config.getString(path);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(path + " es obligatorio");
    return value;
  }

  private FileConfiguration loadConfigFile() {
    YamlConfiguration parsed = new YamlConfiguration();
    try {
      parsed.load(new File(plugin.getDataFolder(), "config.yml"));
    } catch (IOException | InvalidConfigurationException exception) {
      throw new IllegalArgumentException(
          "config.yml no es YAML válido: " + exception.getMessage(), exception);
    }
    try (var stream = plugin.getResource("config.yml")) {
      if (stream != null) {
        parsed.setDefaults(
            YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8)));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("No se pudo leer el config.yml incluido", exception);
    }
    return parsed;
  }
}
