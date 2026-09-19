package net.watones.novacoins.activity;

import java.util.UUID;

public interface ActivityGuard {
  void begin(UUID uuid, long nowNanos);

  void end(UUID uuid);

  void record(UUID uuid, ActivitySignal signal, int pattern, long nowNanos);

  boolean shouldPause(UUID uuid, long nowNanos);
}
