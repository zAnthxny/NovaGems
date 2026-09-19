package net.watones.novacoins.economy;

import java.time.Instant;
import java.util.UUID;

/** Cached account. It changes only after the database has committed a mutation. */
public final class PlayerAccount {
  private final UUID uuid;
  private volatile Snapshot committed;

  public PlayerAccount(
      UUID uuid,
      String name,
      long balance,
      long earned,
      long spent,
      Instant created,
      Instant updated) {
    this(new Snapshot(uuid, name, balance, earned, spent, created, updated));
  }

  public PlayerAccount(Snapshot snapshot) {
    validate(snapshot);
    uuid = snapshot.uuid();
    committed = snapshot;
  }

  public static PlayerAccount newAccount(UUID uuid, String name) {
    Instant now = Instant.now();
    return new PlayerAccount(uuid, name, 0, 0, 0, now, now);
  }

  public UUID uuid() {
    return uuid;
  }

  public String lastName() {
    return committed.lastName();
  }

  public long balance() {
    return committed.balance();
  }

  public long lifetimeEarned() {
    return committed.lifetimeEarned();
  }

  public long lifetimeSpent() {
    return committed.lifetimeSpent();
  }

  public Instant createdAt() {
    return committed.createdAt();
  }

  public Instant updatedAt() {
    return committed.updatedAt();
  }

  public Snapshot snapshot() {
    return committed;
  }

  void applyCommitted(Snapshot snapshot) {
    validate(snapshot);
    if (!uuid.equals(snapshot.uuid())) throw new IllegalArgumentException("Wrong account snapshot");
    committed = snapshot;
  }

  private static void validate(Snapshot snapshot) {
    if (snapshot.balance() < 0
        || snapshot.lifetimeEarned() < 0
        || snapshot.lifetimeSpent() < 0) {
      throw new IllegalArgumentException("Negative account value");
    }
  }

  public record Snapshot(
      UUID uuid,
      String lastName,
      long balance,
      long lifetimeEarned,
      long lifetimeSpent,
      Instant createdAt,
      Instant updatedAt) {}
}
