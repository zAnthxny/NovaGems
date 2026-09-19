package net.watones.novagems.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RollingActivityProfileTest {
  private static final long SECOND = Duration.ofSeconds(1).toNanos();

  @Test
  void ordinaryMixedGameplayIsNeverFlagged() {
    RollingActivityProfile profile = new RollingActivityProfile(0, 256);
    ActivitySignal[] legitimate = {
      ActivitySignal.NORMAL,
      ActivitySignal.BLOCK_BREAK,
      ActivitySignal.BLOCK_PLACE,
      ActivitySignal.INVENTORY,
      ActivitySignal.CHAT,
      ActivitySignal.INTERACT
    };
    for (int index = 1; index <= 180; index++) {
      profile.add(legitimate[index % legitimate.length], index * 37, index * SECOND);
    }
    assertThat(profile.repetitive(180 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isFalse();
  }

  @Test
  void fixedIntervalAutoclickPatternIsFlaggedAfterObservationWindow() {
    RollingActivityProfile profile = new RollingActivityProfile(0, 256);
    for (int index = 1; index <= 150; index++) {
      profile.add(ActivitySignal.INTERACT, 7, index * SECOND);
    }
    assertThat(profile.repetitive(150 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isTrue();
  }

  @Test
  void boundedRingNeverGrowsPastCapacity() {
    RollingActivityProfile profile = new RollingActivityProfile(0, 64);
    for (int index = 1; index <= 1_000; index++) {
      profile.add(ActivitySignal.ROTATION, index % 4, index * SECOND);
    }
    assertThat(profile.size()).isEqualTo(64);
  }

  @Test
  void afkAndStraightMovementAreNotFalsePositives() {
    RollingActivityProfile afk = new RollingActivityProfile(0, 256);
    assertThat(afk.repetitive(300 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isFalse();

    RollingActivityProfile straight = new RollingActivityProfile(0, 256);
    for (int index = 1; index <= 150; index++) {
      straight.add(ActivitySignal.NORMAL, index, index * SECOND);
    }
    assertThat(straight.repetitive(150 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isFalse();
  }

  @Test
  void miningAndBuildingRemainLegitimateEvenWhenIntensive() {
    RollingActivityProfile profile = new RollingActivityProfile(0, 256);
    for (int index = 1; index <= 150; index++) {
      ActivitySignal signal =
          index % 2 == 0 ? ActivitySignal.BLOCK_BREAK : ActivitySignal.BLOCK_PLACE;
      profile.add(signal, index, index * SECOND);
    }
    assertThat(profile.repetitive(150 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isFalse();
  }

  @Test
  void periodicJumpRotationAndShortRouteAreDetected() {
    for (ActivitySignal signal :
        new ActivitySignal[] {ActivitySignal.JUMP, ActivitySignal.ROTATION}) {
      RollingActivityProfile profile = new RollingActivityProfile(0, 256);
      for (int index = 1; index <= 150; index++) {
        profile.add(signal, 4, index * SECOND);
      }
      assertThat(profile.repetitive(150 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
          .isTrue();
    }

    RollingActivityProfile loop = new RollingActivityProfile(0, 256);
    for (int index = 1; index <= 150; index++) {
      loop.add(ActivitySignal.NORMAL, index % 4, index * SECOND);
    }
    assertThat(loop.repetitive(150 * SECOND, 120 * SECOND, 100, 0.97, 10 * SECOND))
        .isTrue();
  }

  @Test
  void fishingLikeInteractionWithHumanVariationIsNotFlagged() {
    RollingActivityProfile profile = new RollingActivityProfile(0, 256);
    long now = 0;
    int random = 17;
    for (int index = 1; index <= 150; index++) {
      random = random * 1103515245 + 12345;
      now += (3 + Math.floorMod(random, 8)) * SECOND;
      profile.add(ActivitySignal.INTERACT, Math.floorMod(random >>> 8, 19), now);
    }
    assertThat(profile.repetitive(now, 120 * SECOND, 100, 0.97, 10 * SECOND)).isFalse();
  }
}
