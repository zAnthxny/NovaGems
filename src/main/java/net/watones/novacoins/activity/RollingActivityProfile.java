package net.watones.novacoins.activity;

/** Fixed-memory rolling statistics with O(1) event ingestion. */
public final class RollingActivityProfile {
  private static final int HISTOGRAM_SIZE = 512;
  private static final long MIN_SAMPLE_GAP_NANOS = 250_000_000L;
  private final long startedNanos;
  private final byte[] signals;
  private final int[] signatures;
  private final long[] times;
  private final int[] histogram = new int[HISTOGRAM_SIZE];
  private final int[] signalCounts = new int[ActivitySignal.values().length];
  private int head;
  private int size;
  private long lastSampleNanos;
  private long lastEvaluationNanos = Long.MIN_VALUE;
  private boolean cachedDecision;

  public RollingActivityProfile(long startedNanos, int capacity) {
    if (capacity < 32) throw new IllegalArgumentException("capacity must be at least 32");
    this.startedNanos = startedNanos;
    this.lastSampleNanos = startedNanos;
    this.signals = new byte[capacity];
    this.signatures = new int[capacity];
    this.times = new long[capacity];
  }

  public synchronized void add(ActivitySignal signal, int pattern, long nowNanos) {
    long delta = Math.max(0, nowNanos - lastSampleNanos);
    if (delta < MIN_SAMPLE_GAP_NANOS) return;
    int intervalBucket = (int) Math.min(255, delta / 50_000_000L);
    int signature = mix(signal.ordinal(), pattern, intervalBucket);
    int slot;
    if (size == signals.length) {
      slot = head;
      removeAt(slot);
      head = (head + 1) % signals.length;
    } else {
      slot = (head + size) % signals.length;
      size++;
    }
    signals[slot] = (byte) signal.ordinal();
    signatures[slot] = signature;
    times[slot] = nowNanos;
    histogram[bucket(signature)]++;
    signalCounts[signal.ordinal()]++;
    lastSampleNanos = nowNanos;
  }

  public synchronized boolean repetitive(
      long nowNanos,
      long minimumObservationNanos,
      int minimumSamples,
      double threshold,
      long evaluationIntervalNanos) {
    if (lastEvaluationNanos != Long.MIN_VALUE
        && nowNanos - lastEvaluationNanos < evaluationIntervalNanos) return cachedDecision;
    lastEvaluationNanos = nowNanos;
    cachedDecision = evaluate(nowNanos, minimumObservationNanos, minimumSamples, threshold);
    return cachedDecision;
  }

  public synchronized int size() {
    return size;
  }

  private boolean evaluate(
      long nowNanos, long minimumObservationNanos, int minimumSamples, double threshold) {
    if (nowNanos - startedNanos < minimumObservationNanos || size < minimumSamples) return false;
    int last = physical(size - 1);
    if (times[last] - times[head] < minimumObservationNanos) return false;

    int benign =
        signalCounts[ActivitySignal.BLOCK_BREAK.ordinal()]
            + signalCounts[ActivitySignal.BLOCK_PLACE.ordinal()]
            + signalCounts[ActivitySignal.INVENTORY.ordinal()]
            + signalCounts[ActivitySignal.CHAT.ordinal()];
    if (benign >= Math.max(3, size / 10)) return false;

    int suspiciousKinds = 0;
    for (ActivitySignal signal : ActivitySignal.values()) {
      if (signalCounts[signal.ordinal()] > 0 && suspicious(signal)) suspiciousKinds++;
    }
    if (suspiciousKinds == 0 || suspiciousKinds > 2) return false;

    int dominant = 0;
    for (int count : histogram) dominant = Math.max(dominant, count);
    if (dominant / (double) size >= threshold) return true;

    int sampleWindow = Math.min(size, 512);
    for (int period = 1; period <= 16 && period < sampleWindow / 4; period++) {
      int matches = 0;
      int comparisons = sampleWindow - period;
      int start = size - sampleWindow;
      for (int index = period; index < sampleWindow; index++) {
        if (signatures[physical(start + index)]
            == signatures[physical(start + index - period)]) matches++;
      }
      if (matches / (double) comparisons >= threshold) return true;
    }
    return false;
  }

  private void removeAt(int slot) {
    histogram[bucket(signatures[slot])]--;
    signalCounts[Byte.toUnsignedInt(signals[slot])]--;
  }

  private int physical(int logicalIndex) {
    return (head + logicalIndex) % signals.length;
  }

  private static boolean suspicious(ActivitySignal signal) {
    return switch (signal) {
      case NORMAL, INTERACT, ATTACK, COMMAND, ITEM_CHANGE, JUMP, ROTATION -> true;
      default -> false;
    };
  }

  private static int mix(int signal, int pattern, int interval) {
    int value = pattern * 0x9E3779B9;
    value ^= signal * 0x85EBCA6B;
    value ^= interval * 0xC2B2AE35;
    value ^= value >>> 16;
    return value;
  }

  private static int bucket(int signature) {
    return signature & (HISTOGRAM_SIZE - 1);
  }
}
