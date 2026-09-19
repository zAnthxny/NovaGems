package net.watones.novacoins.session;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {
  private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

  public PlayerSession connect(UUID uuid, long nowNanos) {
    PlayerSession session = new PlayerSession(uuid, nowNanos);
    sessions.put(uuid, session);
    return session;
  }

  public void disconnect(UUID uuid) {
    sessions.remove(uuid);
  }

  public Optional<PlayerSession> get(UUID uuid) {
    return Optional.ofNullable(sessions.get(uuid));
  }

  public Collection<PlayerSession> all() {
    return sessions.values();
  }

  public int size() {
    return sessions.size();
  }

  public void clear() {
    sessions.clear();
  }
}
