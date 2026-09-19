package net.watones.novacoins.shop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ShopLayout(
    int size,
    int profileSlot,
    int previousSlot,
    int infoSlot,
    int categorySlot,
    int closeSlot,
    int nextSlot,
    List<Integer> rewardSlots) {
  public ShopLayout {
    if (size < 9 || size > 54 || size % 9 != 0) {
      throw new IllegalArgumentException("gui.size debe ser múltiplo de 9 entre 9 y 54");
    }
    int[] reserved = {profileSlot, previousSlot, infoSlot, categorySlot, closeSlot, nextSlot};
    Set<Integer> occupied = new HashSet<>();
    for (int slot : reserved) {
      validateRange(slot, size, "slot reservado");
      if (!occupied.add(slot)) throw new IllegalArgumentException("slots reservados duplicados");
    }
    rewardSlots = List.copyOf(rewardSlots);
    if (rewardSlots.isEmpty()) throw new IllegalArgumentException("gui.reward-slots está vacío");
    for (int slot : rewardSlots) {
      validateRange(slot, size, "reward slot");
      if (!occupied.add(slot)) {
        throw new IllegalArgumentException("reward slot duplicado o reservado: " + slot);
      }
    }
  }

  private static void validateRange(int slot, int size, String label) {
    if (slot < 0 || slot >= size) throw new IllegalArgumentException(label + " fuera de rango: " + slot);
  }
}
