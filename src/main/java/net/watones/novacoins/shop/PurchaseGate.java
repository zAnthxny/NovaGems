package net.watones.novacoins.shop;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PurchaseGate {
  private final Set<UUID> locks = ConcurrentHashMap.newKeySet();

  public boolean tryLock(UUID uuid) {
    return locks.add(uuid);
  }

  public void unlock(UUID uuid) {
    locks.remove(uuid);
  }

  public <T> T run(UUID uuid, Supplier<T> operation, Supplier<T> busy) {
    if (!locks.add(uuid)) return busy.get();
    try {
      return operation.get();
    } finally {
      locks.remove(uuid);
    }
  }

  public boolean locked(UUID uuid) {
    return locks.contains(uuid);
  }
}
