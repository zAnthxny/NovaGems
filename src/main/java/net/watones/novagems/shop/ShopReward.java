package net.watones.novagems.shop;

import java.util.List;
import org.bukkit.Material;

public record ShopReward(
    String id,
    int page,
    String category,
    int slot,
    Material icon,
    boolean glow,
    String name,
    List<String> lore,
    long price,
    boolean confirmation,
    boolean quantitySelectable,
    boolean quantityPanels,
    List<RewardAction> actions) {
  public ShopReward {
    lore = List.copyOf(lore);
    actions = List.copyOf(actions);
  }
}
