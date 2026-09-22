package net.watones.novagems.util;

import java.util.Locale;

/**
 * Number formatting for chat, menus and placeholders.
 *
 * <p>These run on the primary thread for every PlaceholderAPI request a scoreboard makes, and
 * hundreds of times per shop menu render, so they are written as allocation-light integer
 * arithmetic instead of {@code java.text.NumberFormat} (building a compact instance alone cost
 * ~21us per call, and reusing one still cost ~5.6us). Output is byte-identical to the previous
 * {@code NumberFormat}-based implementation.
 */
public final class Formatters {
  private static final char[] COMPACT_UNITS = {'k', 'm', 'b', 't'};

  private Formatters() {}

  /** Grouped decimal form: {@code 1,234,567}. */
  public static String number(long value) {
    if (value == Long.MIN_VALUE) return "-9,223,372,036,854,775,808";
    if (value < 0) return "-" + number(-value);
    if (value < 1000) return Long.toString(value);
    char[] digits = Long.toString(value).toCharArray();
    char[] out = new char[digits.length + (digits.length - 1) / 3];
    int write = out.length;
    for (int read = digits.length, seen = 0; read > 0; ) {
      out[--write] = digits[--read];
      if (++seen % 3 == 0 && read > 0) out[--write] = ',';
    }
    return new String(out);
  }

  public static String duration(long seconds) {
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  public static String signed(long value) {
    return (value >= 0 ? "+" : "") + number(value);
  }

  /** Compact form for tight spaces (scoreboards): 999, 1k, 1.5k, 10m. */
  public static String compact(long value) {
    if (value == Long.MIN_VALUE) return "-9223372t";
    if (value < 0) return "-" + compact(-value);
    if (value < 1000) return Long.toString(value);

    int unit = 0;
    long divisor = 1000L;
    while (unit < COMPACT_UNITS.length - 1 && value / divisor >= 1000L) {
      divisor *= 1000L;
      unit++;
    }
    // Half-up rounding to a single fraction digit, carrying into the next unit when it overflows.
    long whole = value / divisor;
    long tenths = ((value % divisor) * 10 + divisor / 2) / divisor;
    if (tenths == 10) {
      whole++;
      tenths = 0;
    }
    if (whole >= 1000 && unit < COMPACT_UNITS.length - 1) {
      unit++;
      whole /= 1000;
      tenths = 0;
    }
    char suffix = COMPACT_UNITS[unit];
    return tenths == 0 ? whole + String.valueOf(suffix) : whole + "." + tenths + suffix;
  }
}
