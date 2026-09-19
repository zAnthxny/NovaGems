package net.watones.novagems.shop;

public final class RewardSafety {
  private RewardSafety() {}

  public static boolean irreversibleCountAllowed(long commandCount, boolean explicitOverride) {
    return commandCount <= 1 || explicitOverride;
  }
}
