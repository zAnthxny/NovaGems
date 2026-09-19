package net.watones.novagems.session;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory daily kill counter for the kill-reward perk. Bounded by distinct player count, not
 * event volume, and resets naturally at local midnight. Does not survive a server restart.
 */
public final class DailyKillTracker {
  private final Map<UUID, Entry> counts = new HashMap<>();
  private final ZoneId zone;

  public DailyKillTracker() {
    this(ZoneId.systemDefault());
  }

  public DailyKillTracker(ZoneId zone) {
    this.zone = zone;
  }

  /**
   * Registers a kill for today. Returns the resulting count, or -1 if dailyLimit was already
   * reached before this call. A non-positive dailyLimit means unlimited.
   */
  public synchronized int registerKill(UUID killer, int dailyLimit) {
    Entry entry = today(killer);
    if (dailyLimit > 0 && entry.count >= dailyLimit) return -1;
    entry.count++;
    return entry.count;
  }

  public synchronized int countToday(UUID killer) {
    return today(killer).count;
  }

  private Entry today(UUID killer) {
    LocalDate today = LocalDate.now(zone);
    Entry entry = counts.get(killer);
    if (entry == null || !entry.day.equals(today)) {
      entry = new Entry(today);
      counts.put(killer, entry);
    }
    return entry;
  }

  private static final class Entry {
    private final LocalDate day;
    private int count;
    private Entry(LocalDate day) { this.day = day; }
  }
}
