package net.watones.novacoins.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerSession {
  private final UUID uuid;
  private final UUID sessionId;
  private final long startedNanos;
  private final SessionAccumulator timer;
  private boolean activityPaused;
  private boolean rewardBackpressurePaused;
  private long nextCycleNumber;

  public PlayerSession(UUID uuid, long nowNanos) {
    this.uuid = uuid;
    this.sessionId = UUID.randomUUID();
    this.startedNanos = nowNanos;
    this.timer = new SessionAccumulator(nowNanos);
  }

  public UUID uuid() { return uuid; }
  public UUID sessionId() { return sessionId; }
  public long startedNanos() { return startedNanos; }
  public SessionAccumulator timer() { return timer; }
  public boolean paused() { return activityPaused || rewardBackpressurePaused; }
  public boolean activityPaused() { return activityPaused; }
  public void activityPaused(boolean paused) { this.activityPaused = paused; }
  public boolean rewardBackpressurePaused() { return rewardBackpressurePaused; }
  public void rewardBackpressurePaused(boolean paused) { this.rewardBackpressurePaused = paused; }
  /** Compatibility setter: callers that do not distinguish a reason set activity pause. */
  public void paused(boolean paused) { this.activityPaused = paused; }

  /** Materializes only slots already reserved by PendingCompletedRewards; nothing is retained here. */
  public synchronized List<CompletedReward> materializeCompleted(
      int count, long amount, Instant completedAt) {
    List<CompletedReward> completed = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      long cycle = Math.incrementExact(nextCycleNumber);
      completed.add(CompletedReward.create(uuid, amount, completedAt, sessionId, cycle));
    }
    return List.copyOf(completed);
  }
}
