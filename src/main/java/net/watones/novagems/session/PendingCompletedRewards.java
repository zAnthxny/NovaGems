package net.watones.novagems.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded ownership registry independent from online PlayerSession lifetime. */
public final class PendingCompletedRewards {
  private final int primaryCapacity;
  private final int emergencyCapacity;
  private final Map<UUID, Entry> rewards = new LinkedHashMap<>();
  private int reserved;

  public PendingCompletedRewards(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Pending reward capacity must be positive");
    this.primaryCapacity = capacity;
    this.emergencyCapacity = Math.max(1, capacity / 8);
  }

  /** Reserves ownership capacity before a session is allowed to complete cycles. */
  public synchronized Reservation reserveAvailable() {
    int slots = capacity() - rewards.size() - reserved;
    reserved += slots;
    return new Reservation(this, slots);
  }

  private synchronized void commit(Reservation reservation, List<CompletedReward> completed) {
    if (reservation.owner != this || reservation.closed) {
      throw new IllegalStateException("Invalid completed-reward reservation");
    }
    if (completed.size() > reservation.slots) {
      throw new IllegalArgumentException("Completed rewards exceed reservation");
    }
    for (CompletedReward reward : completed) {
      if (rewards.putIfAbsent(reward.operationId(), new Entry(reward)) != null) {
        throw new IllegalStateException("Duplicate completed reward " + reward.operationId());
      }
    }
    release(reservation);
  }

  private synchronized void release(Reservation reservation) {
    if (reservation.closed) return;
    reserved -= reservation.slots;
    reservation.closed = true;
  }

  public synchronized List<CompletedReward> claim(int limit) {
    List<CompletedReward> claimed = new ArrayList<>();
    for (Entry entry : rewards.values()) {
      if (claimed.size() >= limit) break;
      if (!entry.capturing) {
        entry.capturing = true;
        claimed.add(entry.reward);
      }
    }
    return claimed;
  }

  public synchronized void durable(UUID operationId) { rewards.remove(operationId); }
  public synchronized void retry(UUID operationId) {
    Entry entry = rewards.get(operationId);
    if (entry != null) entry.capturing = false;
  }
  public synchronized int size() { return rewards.size(); }
  public synchronized boolean isFull() { return rewards.size() + reserved >= capacity(); }
  public synchronized boolean primarySaturated() {
    return rewards.size() + reserved >= primaryCapacity;
  }
  public synchronized int remainingCapacity() { return capacity() - rewards.size() - reserved; }
  public int capacity() { return primaryCapacity + emergencyCapacity; }
  public int primaryCapacity() { return primaryCapacity; }

  public static final class Reservation implements AutoCloseable {
    private final PendingCompletedRewards owner;
    private final int slots;
    private boolean closed;
    private Reservation(PendingCompletedRewards owner, int slots) {
      this.owner = owner;
      this.slots = slots;
    }
    public int slots() { return slots; }
    public void commit(List<CompletedReward> completed) { owner.commit(this, completed); }
    @Override public void close() { owner.release(this); }
  }

  private static final class Entry {
    private final CompletedReward reward;
    private boolean capturing;
    private Entry(CompletedReward reward) { this.reward = reward; }
  }
}
