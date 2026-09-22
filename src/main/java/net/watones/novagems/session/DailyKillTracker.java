package net.watones.novagems.session;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory daily kill counter for the kill-reward perk. Bounded by the number of distinct players
 * who killed someone <em>today</em>, not by event volume or by lifetime player count: when the
 * local date rolls over every entry is stale by definition, so the whole map is dropped at once.
 * Does not survive a server restart.
 */
public final class DailyKillTracker {
  /** Returned by {@link #registerKill} when the killer already got credit for this victim today. */
  public static final int DUPLICATE_VICTIM = -2;
  /** Returned by {@link #registerKill} when the killer already hit their daily limit. */
  public static final int LIMIT_REACHED = -1;

  private final Map<UUID, Entry> counts = new HashMap<>();
  private final ZoneId zone;
  private LocalDate currentDay;

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
    rollOverIfNeeded();
    Entry entry = counts.computeIfAbsent(killer, ignored -> new Entry());
    if (entry.victims.contains(victim)) return DUPLICATE_VICTIM;
    if (dailyLimit > 0 && entry.count >= dailyLimit) return LIMIT_REACHED;
    entry.count++;
    entry.victims.add(victim);
    return entry.count;
  }

  /** Read-only: never creates an entry for a player who has not killed anyone today. */
  public synchronized int countToday(UUID killer) {
    rollOverIfNeeded();
    Entry entry = counts.get(killer);
    return entry == null ? 0 : entry.count;
  }

  public synchronized int trackedPlayers() {
    rollOverIfNeeded();
    return counts.size();
  }

  private void rollOverIfNeeded() {
    LocalDate today = LocalDate.now(zone);
    if (today.equals(currentDay)) return;
    counts.clear();
    currentDay = today;
  }

  private static final class Entry {
    private final Set<UUID> victims = new HashSet<>();
    private int count;
  }
}
