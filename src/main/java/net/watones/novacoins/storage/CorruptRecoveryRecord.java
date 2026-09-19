package net.watones.novacoins.storage;

import java.time.Instant;

public record CorruptRecoveryRecord(String filename, Instant detectedAt, String error) {}
