package net.watones.novagems.util;

import java.text.NumberFormat;
import java.util.Locale;

public final class Formatters {
  private Formatters() {}

  public static String number(long value) {
    return NumberFormat.getIntegerInstance(Locale.US).format(value);
  }

  public static String duration(long seconds) {
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  public static String signed(long value) {
    return (value >= 0 ? "+" : "") + number(value);
  }

  /** Compact form for tight spaces (scoreboards): 999, 1k, 1.5k, 10m. */
  public static String compact(long value) {
    if (value < 0) return "-" + compact(-value);
    if (value < 1000) return Long.toString(value);
    NumberFormat compact = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
    compact.setMaximumFractionDigits(1);
    return compact.format(value).toLowerCase(Locale.ROOT);
  }
}
