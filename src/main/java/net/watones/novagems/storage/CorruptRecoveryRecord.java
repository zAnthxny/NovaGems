package net.watones.novagems.storage;

import java.time.Instant;

public record CorruptRecoveryRecord(String filename, Instant detectedAt, String error) {}
