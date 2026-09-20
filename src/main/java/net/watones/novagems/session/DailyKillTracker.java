package net.watones.novagems.session;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory daily kill counter for the kill-reward perk. Bounded by distinct player count, not
 * event volume, and resets naturally at local midnight. Does not survive a server restart.
 */
public final class DailyKillTracker {
  /** Returned by {@link #registerKill} when the killer already got credit for this victim today. */
  public static final int DUPLICATE_VICTIM = -2;
  /** Returned by {@link #registerKill} when the killer already hit their daily limit. */
  public static final int LIMIT_REACHED = -1;

  private final Map<UUID, Entry> counts = new HashMap<>();
  private final ZoneId zone;

  public DailyKillTracker() {
    this(ZoneId.systemDefault());
  }

  public DailyKillTracker(ZoneId zone) {
    this.zone = zone;
  }

  /**
   * Registers a kill of {@code victim} by {@code killer} for today. Returns the resulting count,
   * {@link #DUPLICATE_VICTIM} if this killer already earned credit for killing this exact victim
   * today (farming the same player never counts twice), or {@link #LIMIT_REACHED} if dailyLimit
   * was already reached before this call. A non-positive dailyLimit means unlimited.
   */
  public synchronized int registerKill(UUID killer, UUID victim, int dailyLimit) {
    Entry entry = today(killer);
    if (entry.victims.contains(victim)) return DUPLICATE_VICTIM;
    if (dailyLimit > 0 && entry.count >= dailyLimit) return LIMIT_REACHED;
    entry.count++;
    entry.victims.add(victim);
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
    private final Set<UUID> victims = new HashSet<>();
    private int count;
    private Entry(LocalDate day) { this.day = day; }
  }
}
