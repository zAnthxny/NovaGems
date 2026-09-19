package net.watones.novacoins.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionAccumulatorTest {
  @Test
  void boundedCompletionRetainsExcessCyclesForBackpressure() {
    SessionAccumulator timer = new SessionAccumulator(0);
    long interval = 1_000;
    assertThat(timer.update(interval * 5, interval, false, 2)).isEqualTo(2);
    assertThat(timer.update(interval * 5, interval, false, 0)).isZero();
    assertThat(timer.update(interval * 5, interval, false, 3)).isEqualTo(3);
    assertThat(timer.cycles()).isEqualTo(5);
  }

  private static final long INTERVAL = Duration.ofMinutes(30).toNanos();

  @Test
  void boundaryAndMultipleCycles() {
    SessionAccumulator timer = new SessionAccumulator(0);
    assertThat(timer.update(0, INTERVAL, false)).isZero();
    assertThat(timer.update(Duration.ofMinutes(29).plusSeconds(59).toNanos(), INTERVAL, false))
        .isZero();
    assertThat(timer.update(INTERVAL, INTERVAL, false)).isEqualTo(1);
    assertThat(timer.update(Duration.ofMinutes(60).toNanos(), INTERVAL, false)).isEqualTo(1);
    assertThat(timer.cycles()).isEqualTo(2);
  }

  @Test
  void exactDisconnectBoundariesNeverLoseOrInventACompletedCycle() {
    SessionAccumulator before = new SessionAccumulator(0);
    assertThat(before.update(INTERVAL - 1_000_000, INTERVAL, false)).isZero();
    assertThat(before.elapsedNanos()).isEqualTo(INTERVAL - 1_000_000);

    SessionAccumulator after = new SessionAccumulator(0);
    assertThat(after.update(INTERVAL + 1_000_000, INTERVAL, false)).isEqualTo(1);
    assertThat(after.elapsedNanos()).isEqualTo(1_000_000);

    SessionAccumulator longSession = new SessionAccumulator(0);
    assertThat(longSession.update(Duration.ofMinutes(61).toNanos(), INTERVAL, false)).isEqualTo(2);
    assertThat(longSession.elapsedNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
  }

  @Test
  void preservesSubsecondRemainder() {
    SessionAccumulator timer = new SessionAccumulator(0);
    assertThat(timer.update(INTERVAL + Duration.ofMillis(500).toNanos(), INTERVAL, false))
        .isEqualTo(1);
    assertThat(timer.elapsedNanos()).isEqualTo(Duration.ofMillis(500).toNanos());
  }

  @Test
  void pausedTimeIsNeverRecovered() {
    SessionAccumulator timer = new SessionAccumulator(0);
    timer.update(Duration.ofMinutes(10).toNanos(), INTERVAL, false);
    timer.update(Duration.ofMinutes(25).toNanos(), INTERVAL, true);
    assertThat(timer.update(Duration.ofMinutes(45).toNanos(), INTERVAL, false)).isEqualTo(1);
    assertThat(timer.elapsedNanos()).isZero();
  }

  @Test
  void disconnectDestroysProgressAndHistoricalPlaytimeCannotEnterModel() {
    SessionRegistry registry = new SessionRegistry();
    UUID newPlayer = UUID.randomUUID(), veteran = UUID.randomUUID();
    registry
        .connect(newPlayer, 0)
        .timer()
        .update(Duration.ofMinutes(29).toNanos(), INTERVAL, false);
    registry.connect(veteran, 0);
    registry.disconnect(newPlayer);
    PlayerSession reconnect = registry.connect(newPlayer, Duration.ofHours(4000).toNanos());
    assertThat(reconnect.timer().elapsedNanos()).isZero();
    assertThat(registry.get(veteran).orElseThrow().timer().elapsedNanos()).isZero();
    assertThat(reconnect.timer().cycles()).isZero();
  }
}
