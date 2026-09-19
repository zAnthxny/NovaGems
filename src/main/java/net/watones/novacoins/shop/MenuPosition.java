package net.watones.novacoins.shop;

import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

public record MenuPosition(int page, String category) {
  public static MenuPosition resolve(
      int requestedPage,
      String requestedCategory,
      List<String> categories,
      ToIntFunction<String> pageCount) {
    String normalized = "all";
    if (requestedCategory != null && !requestedCategory.equalsIgnoreCase("all")) {
      normalized =
          categories.stream()
              .filter(category -> category.equalsIgnoreCase(requestedCategory))
              .findFirst()
              .map(category -> category.toLowerCase(Locale.ROOT))
              .orElse("all");
    }
    int maximum = Math.max(1, pageCount.applyAsInt(normalized));
    return new MenuPosition(Math.max(1, Math.min(requestedPage, maximum)), normalized);
  }

  public static boolean stale(long holderVersion, long currentVersion) {
    return holderVersion != currentVersion;
  }
}
