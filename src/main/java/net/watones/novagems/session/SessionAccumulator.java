package net.watones.novagems.session;

/**
 * Pure monotonic timer. It has deliberately no concept of vanilla statistics or persisted playtime.
 */
public final class SessionAccumulator {
  private long lastUpdateNanos;
  private long elapsedNanos;
  private long cycles;

  public SessionAccumulator(long startedNanos) {
    this.lastUpdateNanos = startedNanos;
  }

  public int update(long nowNanos, long intervalNanos, boolean paused) {
    return update(nowNanos, intervalNanos, paused, Integer.MAX_VALUE);
  }

  /** Completes at most maxCompleted cycles and retains any additional full intervals. */
  public int update(long nowNanos, long intervalNanos, boolean paused, int maxCompleted) {
    if (intervalNanos <= 0) throw new IllegalArgumentException("intervalNanos must be positive");
    if (maxCompleted < 0) throw new IllegalArgumentException("maxCompleted cannot be negative");
    long delta = Math.max(0, nowNanos - lastUpdateNanos);
    lastUpdateNanos = nowNanos;
    if (paused) return 0;
    elapsedNanos = Math.addExact(elapsedNanos, delta);
    long completed = Math.min(elapsedNanos / intervalNanos, maxCompleted);
    elapsedNanos -= Math.multiplyExact(completed, intervalNanos);
    cycles = Math.addExact(cycles, completed);
    return Math.toIntExact(completed);
  }

  public long elapsedNanos() {
    return elapsedNanos;
  }

  public long cycles() {
    return cycles;
  }
}
