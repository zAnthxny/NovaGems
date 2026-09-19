package net.watones.novagems.config;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.watones.novagems.shop.*;
import net.watones.novagems.util.SoundResolver;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopConfig {
  private final JavaPlugin plugin;
  private final AtomicReference<ShopSnapshot> current = new AtomicReference<>();
  private final MiniMessage mini = MiniMessage.miniMessage();

  public ShopConfig(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public ShopSnapshot current() {
    return current.get();
  }

  public ShopSnapshot loadAndSwap() {
    ShopSnapshot parsed = parseCandidate();
    current.set(parsed);
    return parsed;
  }

  public ShopSnapshot reloadAtomically() {
    ShopSnapshot parsed = parseCandidate();
    current.set(parsed);
    return parsed;
  }

  public ShopSnapshot parseCandidate() {
    File file = new File(plugin.getDataFolder(), "shop.yml");
    YamlConfiguration y = new YamlConfiguration();
    try {
      y.load(file);
    } catch (java.io.IOException | InvalidConfigurationException e) {
      throw new IllegalArgumentException("shop.yml no es YAML válido: " + e.getMessage(), e);
    }
    String title = y.getString("settings.title", "<gold>Canjes"),
        confirm = y.getString("settings.confirmation-title", "<gold>Confirmar");
    int size = y.getInt("gui.size", y.getInt("settings.size", 54));
    ShopLayout layout = parseLayout(y, size);
    mini.deserialize(title);
    mini.deserialize(confirm);
    ConfigurationSection section = y.getConfigurationSection("rewards");
    Map<String, ShopReward> rewards = new LinkedHashMap<>();
    Set<String> slots = new HashSet<>();
    if (section != null)
      for (String id : section.getKeys(false)) {
        try {
          ShopReward reward =
              parseReward(id, Objects.requireNonNull(section.getConfigurationSection(id)), layout);
          if (!slots.add(reward.page() + ":" + reward.slot()))
            throw new IllegalArgumentException("slot duplicado " + reward.slot());
          rewards.put(id, reward);
        } catch (Exception e) {
          plugin.getLogger().warning("Reward '" + id + "' disabled: " + e.getMessage());
        }
      }
    long nextVersion = current.get() == null ? 1 : current.get().version() + 1;
    return new ShopSnapshot(nextVersion, title, size, confirm, rewards, layout);
  }

  public void swap(ShopSnapshot candidate) {
    current.set(candidate);
  }

  public ShopSnapshot validateSafety(
      ShopSnapshot candidate, boolean allowMultipleIrreversibleActions) {
    if (allowMultipleIrreversibleActions) return candidate;
    Map<String, ShopReward> safe = new LinkedHashMap<>();
    for (ShopReward reward : candidate.rewards().values()) {
      long commands = reward.actions().stream().filter(RewardAction.Command.class::isInstance).count();
      if (!RewardSafety.irreversibleCountAllowed(commands, allowMultipleIrreversibleActions)) {
        plugin.getLogger().warning(
            "Reward '" + reward.id()
                + "' disabled: contiene múltiples COMMAND irreversibles");
      } else {
        safe.put(reward.id(), reward);
      }
    }
    return new ShopSnapshot(
        candidate.version(), candidate.title(), candidate.size(), candidate.confirmationTitle(),
        safe, candidate.layout());
  }

  private ShopReward parseReward(String id, ConfigurationSection s, ShopLayout layout) {
    int page = s.getInt("page", 1);
    if (page < 1 || page > 100) throw new IllegalArgumentException("page fuera de rango");
    int slot = s.getInt("slot", -1);
    if (slot < 0 || slot >= layout.size())
      throw new IllegalArgumentException("slot fuera del inventario");
    if (!layout.rewardSlots().contains(slot))
      throw new IllegalArgumentException("slot no está incluido en gui.reward-slots");
    Material material = material(s.getString("material"));
    long price = s.getLong("price", -1);
    if (price < 1) throw new IllegalArgumentException("price must be >= 1");
    String name = required(s, "name");
    List<String> lore = s.getStringList("lore");
    mini.deserialize(name);
    lore.forEach(mini::deserialize);
    List<Map<?, ?>> raw = s.getMapList("actions");
    if (raw.isEmpty()) throw new IllegalArgumentException("actions está vacío");
    List<RewardAction> actions = new ArrayList<>();
    for (Map<?, ?> map : raw) {
      String type = String.valueOf(map.get("type")).toUpperCase(Locale.ROOT);
      switch (type) {
        case "COMMAND" -> actions.add(new RewardAction.Command(requiredMap(map, "value")));
        case "MESSAGE" -> {
          String value = requiredMap(map, "value");
          mini.deserialize(value);
          actions.add(new RewardAction.Message(value));
        }
        case "SOUND" -> {
          Sound sound = SoundResolver.resolve(requiredMap(map, "value"));
          if (sound == null) throw new IllegalArgumentException("sonido desconocido");
          float volume = floatValue(map, "volume", 1), pitch = floatValue(map, "pitch", 1);
          if (!Float.isFinite(volume)
              || !Float.isFinite(pitch)
              || volume < 0
              || pitch < 0
              || pitch > 2) throw new IllegalArgumentException("volume o pitch SOUND inválido");
          actions.add(new RewardAction.Sound(sound, volume, pitch));
        }
        case "ITEM" -> actions.add(new RewardAction.Item(parseItem(map)));
        default -> throw new IllegalArgumentException("action type desconocido: " + type);
      }
    }
    return new ShopReward(
        id,
        page,
        s.getString("category", "general"),
        slot,
        material,
        name,
        lore,
        price,
        s.getBoolean("confirmation", false),
        actions);
  }

  private ShopLayout parseLayout(YamlConfiguration yaml, int size) {
    List<Integer> defaults = new ArrayList<>();
    for (int row = 1; row <= 4; row++) {
      for (int column = 1; column <= 7; column++) defaults.add(row * 9 + column);
    }
    List<Integer> configured = yaml.getIntegerList("gui.reward-slots");
    ShopLayout layout =
        new ShopLayout(
            size,
            yaml.getInt("gui.profile-slot", 4),
            yaml.getInt("gui.previous-slot", 45),
            yaml.getInt("gui.info-slot", 49),
            yaml.getInt("gui.category-slot", 48),
            yaml.getInt("gui.close-slot", 50),
            yaml.getInt("gui.next-slot", 53),
            configured.isEmpty() ? defaults : configured);
    return layout;
  }

  @SuppressWarnings("deprecation")
  private ItemStack parseItem(Map<?, ?> map) {
    Material material = material(requiredMap(map, "material"));
    int amount = intValue(map, "amount", 1);
    if (amount < 1 || amount > material.getMaxStackSize())
      throw new IllegalArgumentException("amount ITEM inválido");
    ItemStack item = new ItemStack(material, amount);
    ItemMeta meta = item.getItemMeta();
    Object name = map.get("name");
    if (name != null) meta.displayName(mini.deserialize(String.valueOf(name)));
    Object lore = map.get("lore");
    if (lore instanceof List<?> list)
      meta.lore(list.stream().map(String::valueOf).map(mini::deserialize).toList());
    Object cmd = map.get("custom-model-data");
    if (cmd instanceof Number number) meta.setCustomModelData(number.intValue());
    Object ench = map.get("enchantments");
    if (ench instanceof Map<?, ?> values)
      for (var entry : values.entrySet()) {
        Enchantment enchant =
            Registry.ENCHANTMENT.get(
                NamespacedKey.minecraft(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)));
        if (enchant == null)
          throw new IllegalArgumentException("encantamiento desconocido: " + entry.getKey());
        int level = Integer.parseInt(String.valueOf(entry.getValue()));
        if (level < 1 || level > 255)
          throw new IllegalArgumentException("nivel de encantamiento fuera de rango");
        meta.addEnchant(enchant, level, true);
      }
    Object flags = map.get("flags");
    if (flags instanceof List<?> list)
      for (Object flag : list)
        meta.addItemFlags(ItemFlag.valueOf(String.valueOf(flag).toUpperCase(Locale.ROOT)));
    item.setItemMeta(meta);
    return item;
  }

  private Material material(String value) {
    if (value == null) throw new IllegalArgumentException("material obligatorio");
    Material m = Material.matchMaterial(value);
    if (m == null || m.isAir())
      throw new IllegalArgumentException("material desconocido: " + value);
    return m;
  }

  private String required(ConfigurationSection s, String path) {
    String v = s.getString(path);
    if (v == null || v.isBlank()) throw new IllegalArgumentException(path + " obligatorio");
    return v;
  }

  private String requiredMap(Map<?, ?> m, String key) {
    Object v = m.get(key);
    if (v == null || String.valueOf(v).isBlank())
      throw new IllegalArgumentException(key + " obligatorio");
    return String.valueOf(v);
  }

  private int intValue(Map<?, ?> m, String key, int fallback) {
    Object v = m.get(key);
    return v == null ? fallback : Integer.parseInt(String.valueOf(v));
  }

  private float floatValue(Map<?, ?> m, String key, float fallback) {
    Object v = m.get(key);
    return v == null ? fallback : Float.parseFloat(String.valueOf(v));
  }
}
