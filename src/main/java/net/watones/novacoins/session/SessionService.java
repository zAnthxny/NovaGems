package net.watones.novacoins.session;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.watones.novacoins.activity.ActivityGuard;
import net.watones.novacoins.config.ConfigManager;
import net.watones.novacoins.economy.EconomyResult;
import net.watones.novacoins.economy.WalletService;
import net.watones.novacoins.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SessionService {
  private final SessionRegistry registry;
  private final ActivityGuard guard;
  private final RewardService rewards;
  private final ConfigManager config;
  private final JavaPlugin plugin;
  private final MessageService messages;
  private final PendingCompletedRewards pendingCompleted;
  private final Set<CompletableFuture<EconomyResult>> inFlightRewards =
      ConcurrentHashMap.newKeySet();
  private volatile boolean accepting = true;

  public SessionService(
      SessionRegistry registry,
      ActivityGuard guard,
      RewardService rewards,
      ConfigManager config,
      JavaPlugin plugin,
      MessageService messages) {
    this(registry, guard, rewards, config, plugin, messages, 4096);
  }

  public SessionService(
      SessionRegistry registry,
      ActivityGuard guard,
      RewardService rewards,
      ConfigManager config,
      JavaPlugin plugin,
      MessageService messages,
      int pendingCompletedCapacity) {
    this.registry = registry;
    this.guard = guard;
    this.rewards = rewards;
    this.config = config;
    this.plugin = plugin;
    this.messages = messages;
    this.pendingCompleted = new PendingCompletedRewards(pendingCompletedCapacity);
  }

  public void connect(UUID uuid) {
    if (!accepting) return;
    long now = System.nanoTime();
    registry.connect(uuid, now);
    guard.begin(uuid, now);
  }

  public void disconnect(UUID uuid) {
    registry.get(uuid).ifPresent(session -> settleFinal(session, System.nanoTime()));
    registry.disconnect(uuid);
    guard.end(uuid);
  }

  public void tick() {
    if (!accepting) return;
    capturePending();
    long now = System.nanoTime();
    long interval = Math.multiplyExact(config.current().intervalSeconds(), 1_000_000_000L);
    for (PlayerSession session : registry.all()) {
      Player player = Bukkit.getPlayer(session.uuid());
      if (player == null) {
        disconnect(session.uuid());
        continue;
      }
      boolean wasActivityPaused = session.activityPaused();
      boolean activityPaused = guard.shouldPause(session.uuid(), now);
      boolean backpressurePaused = pendingCompleted.isFull();
      session.activityPaused(activityPaused);
      session.rewardBackpressurePaused(backpressurePaused);
      if (activityPaused != wasActivityPaused) notifyTransition(player, activityPaused);
      captureCycles(session, now, interval, activityPaused || backpressurePaused);
    }
  }

  public SessionRegistry registry() { return registry; }

  public void clear() {
    for (PlayerSession session : List.copyOf(registry.all())) disconnect(session.uuid());
  }

  private void settleFinal(PlayerSession session, long now) {
    long interval = Math.multiplyExact(config.current().intervalSeconds(), 1_000_000_000L);
    boolean backpressurePaused = pendingCompleted.isFull();
    session.rewardBackpressurePaused(backpressurePaused);
    captureCycles(session, now, interval, session.activityPaused() || backpressurePaused);
  }

  private void captureCycles(PlayerSession session, long now, long interval, boolean paused) {
    try (PendingCompletedRewards.Reservation reservation = pendingCompleted.reserveAvailable()) {
      int completed = session.timer().update(now, interval, paused, reservation.slots());
      List<CompletedReward> materialized = session.materializeCompleted(
          completed, config.current().coinsPerInterval(), Instant.now());
      reservation.commit(materialized);
    }
    capturePending();
  }

  private void capturePending() {
    for (CompletedReward reward : pendingCompleted.claim(64)) {
      WalletService.OperationSubmission submission = rewards.capture(reward);
      if (!submission.captured()) {
        pendingCompleted.retry(reward.operationId());
        continue;
      }
      submission.durability().whenComplete((ignored, error) -> {
        if (error == null) pendingCompleted.durable(reward.operationId());
        else pendingCompleted.retry(reward.operationId());
      });
      CompletableFuture<EconomyResult> future = submission.result();
      inFlightRewards.add(future);
      future.whenComplete((ignored, error) -> inFlightRewards.remove(future));
    }
  }

  public SessionProgress progress(UUID uuid) {
    return registry.get(uuid).map(session -> {
      long intervalNanos =
          Math.multiplyExact(config.current().intervalSeconds(), 1_000_000_000L);
      long remainingNanos = Math.max(0, intervalNanos - session.timer().elapsedNanos());
      long seconds = Math.max(0, (remainingNanos + 999_999_999L) / 1_000_000_000L);
      return new SessionProgress(true, session.paused(), seconds);
    }).orElseGet(() -> new SessionProgress(false, false, 0));
  }

  public int pausedCount() {
    int count = 0;
    for (PlayerSession session : registry.all()) if (session.paused()) count++;
    return count;
  }

  public int pendingCompletedRewards() { return pendingCompleted.size(); }
  public int pendingCompletedCapacity() { return pendingCompleted.capacity(); }
  public boolean rewardBackpressure() { return pendingCompleted.isFull(); }

  public void notifyPendingRewards(UUID uuid) { rewards.notifyPending(uuid); }

  /** Stops progression and waits only until every completed reward is journal-durable. */
  public ShutdownReport shutdownAndDrain(long deadlineNanos) {
    accepting = false;
    for (PlayerSession session : List.copyOf(registry.all())) {
      settleFinal(session, System.nanoTime());
      registry.disconnect(session.uuid());
      guard.end(session.uuid());
    }
    while (pendingCompleted.size() > 0 && System.nanoTime() < deadlineNanos) {
      capturePending();
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return new ShutdownReport(pendingCompleted.size(), inFlightRewards.size());
  }

  private void notifyTransition(Player player, boolean paused) {
    var notifications = config.current().antiAbuse();
    if (notifications.notifyPlayer()) {
      messages.send(player, paused ? "activity-paused" : "activity-resumed");
    }
    if (notifications.notifyConsole()) {
      plugin.getLogger().info(
          "ActivityGuard " + (paused ? "paused " : "resumed ") + player.getName()
              + " (" + player.getUniqueId() + ")");
    }
  }

  public record SessionProgress(boolean available, boolean paused, long remainingSeconds) {}
  public record ShutdownReport(int completedRewardsNotDurable, int databaseRewardsInFlight) {}
}
