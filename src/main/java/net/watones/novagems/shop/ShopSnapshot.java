package net.watones.novagems.shop;

import java.util.Map;

public record ShopSnapshot(
    long version,
    String title,
    int size,
    String confirmationTitle,
    Map<String, ShopReward> rewards,
    ShopLayout layout) {
  public ShopSnapshot {
    rewards = Map.copyOf(rewards);
  }

  public int pageCount() {
    return pageCount("all");
  }

  public int pageCount(String category) {
    return rewards.values().stream()
        .filter(reward -> "all".equals(category) || reward.category().equalsIgnoreCase(category))
        .mapToInt(ShopReward::page)
        .max()
        .orElse(1);
  }

  public java.util.List<String> categories() {
    return rewards.values().stream()
        .map(ShopReward::category)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList();
  }

  public boolean categorySelectorVisible() {
    return categories().size() > 1;
  }
}
