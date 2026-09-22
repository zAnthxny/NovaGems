package net.watones.novagems.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyKillTrackerTest {
  @Test
  void seedRestoresTodaysLimitSoARestartDoesNotGrantAFreshAllowance() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();
    Set<UUID> alreadyKilled = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    tracker.seed(tracker.todayKey(), Map.of(killer, alreadyKilled));

    assertThat(tracker.countToday(killer)).isEqualTo(3);
    // The limit now counts from the restored total, not from zero.
    assertThat(tracker.registerKill(killer, UUID.randomUUID(), 3))
        .isEqualTo(DailyKillTracker.LIMIT_REACHED);
  }

  @Test
  void seedKeepsVictimsAlreadyPaidForBeforeTheRestartLoadLanded() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();
    UUID persistedVictim = UUID.randomUUID();
    UUID victimKilledWhileLoading = UUID.randomUUID();

    assertThat(tracker.registerKill(killer, victimKilledWhileLoading, 10)).isEqualTo(1);
    tracker.seed(tracker.todayKey(), Map.of(killer, Set.of(persistedVictim)));

    assertThat(tracker.countToday(killer)).isEqualTo(2);
    assertThat(tracker.registerKill(killer, victimKilledWhileLoading, 10))
        .isEqualTo(DailyKillTracker.DUPLICATE_VICTIM);
    assertThat(tracker.registerKill(killer, persistedVictim, 10))
        .isEqualTo(DailyKillTracker.DUPLICATE_VICTIM);
  }

  @Test
  void seedForAnotherDayIsIgnored() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();

    tracker.seed("1999-01-01", Map.of(killer, Set.of(UUID.randomUUID(), UUID.randomUUID())));

    assertThat(tracker.countToday(killer)).isZero();
  }

  @Test
  void readingACountNeverCreatesAnEntry() {
    DailyKillTracker tracker = new DailyKillTracker();

    assertThat(tracker.countToday(UUID.randomUUID())).isZero();
    assertThat(tracker.trackedPlayers()).isZero();
  }

  @Test
  void firstKillOfEachDistinctVictimCounts() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();
    UUID victimA = UUID.randomUUID();
    UUID victimB = UUID.randomUUID();

    assertThat(tracker.registerKill(killer, victimA, 10)).isEqualTo(1);
    assertThat(tracker.registerKill(killer, victimB, 10)).isEqualTo(2);
  }

  @Test
  void repeatedVictimNeverCountsTwiceEvenBelowTheLimit() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();
    UUID victim = UUID.randomUUID();

    assertThat(tracker.registerKill(killer, victim, 10)).isEqualTo(1);
    assertThat(tracker.registerKill(killer, victim, 10))
        .isEqualTo(DailyKillTracker.DUPLICATE_VICTIM);
    assertThat(tracker.registerKill(killer, victim, 10))
        .isEqualTo(DailyKillTracker.DUPLICATE_VICTIM);
    assertThat(tracker.countToday(killer)).isEqualTo(1);
  }

  @Test
  void dailyLimitBlocksFurtherDistinctVictimsOnceReached() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();

    assertThat(tracker.registerKill(killer, UUID.randomUUID(), 2)).isEqualTo(1);
    assertThat(tracker.registerKill(killer, UUID.randomUUID(), 2)).isEqualTo(2);
    assertThat(tracker.registerKill(killer, UUID.randomUUID(), 2))
        .isEqualTo(DailyKillTracker.LIMIT_REACHED);
    assertThat(tracker.countToday(killer)).isEqualTo(2);
  }

  @Test
  void nonPositiveDailyLimitMeansUnlimitedDistinctVictims() {
    DailyKillTracker tracker = new DailyKillTracker();
    UUID killer = UUID.randomUUID();

    for (int i = 0; i < 50; i++) {
      assertThat(tracker.registerKill(killer, UUID.randomUUID(), 0)).isEqualTo(i + 1);
    }
  }
}
