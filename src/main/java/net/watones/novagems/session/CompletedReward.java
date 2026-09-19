package net.watones.novagems.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable identity of one completed session cycle. Retries retain this operation ID. */
public record CompletedReward(
    UUID operationId,
    UUID playerUuid,
    long amount,
    Instant completedAt,
    UUID sessionId,
    long cycleNumber) {
  public CompletedReward {
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(completedAt);
    Objects.requireNonNull(sessionId);
    if (amount <= 0 || cycleNumber <= 0) throw new IllegalArgumentException("Invalid reward");
  }

  public static CompletedReward create(
      UUID playerUuid, long amount, Instant completedAt, UUID sessionId, long cycleNumber) {
    return new CompletedReward(
        UUID.randomUUID(), playerUuid, amount, completedAt, sessionId, cycleNumber);
  }

  public String reference() {
    return "session:" + sessionId + ":" + cycleNumber;
  }
}
