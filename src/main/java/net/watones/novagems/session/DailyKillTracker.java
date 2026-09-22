package net.watones.novagems.session;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Daily kill counter for the kill-reward perk.
 *
 * <p>Authoritative in memory while the server runs, and bounded by the number of distinct players
 * who killed someone <em>today</em>: when the local date rolls over every entry is stale by
 * definition, so the whole map is dropped at once. {@link #seed} restores the current day from
 * storage at startup, so a restart part-way through the day does not hand everyone a fresh daily
 * allowance.
 *
 * <p>A killer's daily total is exactly the number of distinct victims they have been paid for, so
 * the victim set is the only state there is to keep.
 */
public final class DailyKillTracker {
  /** Returned by {@link #registerKill} when the killer already got credit for this victim today. */
  public static final int DUPLICATE_VICTIM = -2;
  /** Returned by {@link #registerKill} when the killer already hit their daily limit. */
  public static final int LIMIT_REACHED = -1;

  private final Map<UUID, Set<UUID>> victimsByKiller = new HashMap<>();
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
    Set<UUID> victims = victimsByKiller.computeIfAbsent(killer, ignored -> new HashSet<>());
    if (victims.contains(victim)) return DUPLICATE_VICTIM;
    if (dailyLimit > 0 && victims.size() >= dailyLimit) return LIMIT_REACHED;
    victims.add(victim);
    return victims.size();
  }

  /**
   * Merges persisted state for {@code day} into memory. Merging rather than replacing keeps any
   * kill that landed between enable and the storage read finishing.
   */
  public synchronized void seed(String day, Map<UUID, Set<UUID>> persisted) {
    if (!day.equals(todayKey())) return;
    rollOverIfNeeded();
    persisted.forEach(
        (killer, victims) ->
            victimsByKiller.computeIfAbsent(killer, ignored -> new HashSet<>()).addAll(victims));
  }

  /** Read-only: never creates an entry for a player who has not killed anyone today. */
  public synchronized int countToday(UUID killer) {
    rollOverIfNeeded();
    Set<UUID> victims = victimsByKiller.get(killer);
    return victims == null ? 0 : victims.size();
  }

  public synchronized int trackedPlayers() {
    rollOverIfNeeded();
    return victimsByKiller.size();
  }

  /** ISO local date used as the storage key, resolved with this tracker's zone. */
  public String todayKey() {
    return LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE);
  }

  private void rollOverIfNeeded() {
    LocalDate today = LocalDate.now(zone);
    if (today.equals(currentDay)) return;
    victimsByKiller.clear();
    currentDay = today;
  }
}
