package net.watones.novacoins.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

public final class SoundResolver {
  private SoundResolver() {}

  public static Sound resolve(String configured) {
    if (configured == null || configured.isBlank()) return null;
    String value = configured.trim().toLowerCase(java.util.Locale.ROOT);
    NamespacedKey key =
        value.indexOf(':') >= 0 ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
    return key == null ? null : Registry.SOUND_EVENT.get(key);
  }
}
