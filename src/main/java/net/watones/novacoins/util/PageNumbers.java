package net.watones.novacoins.util;

import java.util.OptionalInt;

public final class PageNumbers {
  public static final int MAX_PAGE = 100_000;

  private PageNumbers() {}

  public static OptionalInt parse(String raw) {
    try {
      int page = Integer.parseInt(raw);
      return page >= 1 && page <= MAX_PAGE ? OptionalInt.of(page) : OptionalInt.empty();
    } catch (NumberFormatException ignored) {
      return OptionalInt.empty();
    }
  }
}
