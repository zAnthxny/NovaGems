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
}
