package net.watones.novacoins.storage;

public enum JournalHealth {
  INITIALIZING,
  HEALTHY,
  BACKPRESSURE,
  UNAVAILABLE,
  SHUTTING_DOWN
}
