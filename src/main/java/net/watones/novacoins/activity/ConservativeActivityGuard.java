package net.watones.novacoins.activity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.watones.novacoins.config.ConfigManager;

public final class ConservativeActivityGuard implements ActivityGuard {
  private static final int MAX_SAMPLES = 2048;
  private final ConfigManager config;
  private final Map<UUID, RollingActivityProfile> profiles = new ConcurrentHashMap<>();

  public ConservativeActivityGuard(ConfigManager config) {
    this.config = config;
  }

  @Override
  public void begin(UUID uuid, long now) {
    profiles.put(uuid, new RollingActivityProfile(now, MAX_SAMPLES));
  }

  @Override
  public void end(UUID uuid) {
    profiles.remove(uuid);
  }

  @Override
  public void record(UUID uuid, ActivitySignal signal, int pattern, long now) {
    RollingActivityProfile profile = profiles.get(uuid);
    if (profile != null) profile.add(signal, pattern, now);
  }

  @Override
  public boolean shouldPause(UUID uuid, long now) {
    var settings = config.current().antiAbuse();
    if (!settings.enabled()) return false;
    RollingActivityProfile profile = profiles.get(uuid);
    return profile != null
        && profile.repetitive(
            now,
            settings.minimumObservationSeconds() * 1_000_000_000L,
            settings.minimumSamples(),
            settings.threshold(),
            settings.evaluationIntervalSeconds() * 1_000_000_000L);
  }
}
