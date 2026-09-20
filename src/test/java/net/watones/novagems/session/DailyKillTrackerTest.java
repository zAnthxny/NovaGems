package net.watones.novagems.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyKillTrackerTest {
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
