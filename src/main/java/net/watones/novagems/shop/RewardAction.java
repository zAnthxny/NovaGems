package net.watones.novagems.shop;

import org.bukkit.inventory.ItemStack;

public sealed interface RewardAction
    permits RewardAction.Command, RewardAction.Item, RewardAction.Message, RewardAction.Sound {
  ActionSafety safety();

  record Command(String value) implements RewardAction {
    @Override
    public ActionSafety safety() {
      return ActionSafety.IRREVERSIBLE;
    }
  }

  record Item(ItemStack value) implements RewardAction {
    @Override
    public ActionSafety safety() {
      return ActionSafety.REVERSIBLE;
    }
  }

  record Message(String value) implements RewardAction {
    @Override
    public ActionSafety safety() {
      return ActionSafety.COSMETIC;
    }
  }

  record Sound(org.bukkit.Sound value, float volume, float pitch) implements RewardAction {
    @Override
    public ActionSafety safety() {
      return ActionSafety.COSMETIC;
    }
  }
}
