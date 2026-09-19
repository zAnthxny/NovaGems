package net.watones.novacoins.message;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {
  private final JavaPlugin plugin;
  private final MiniMessage mini = MiniMessage.miniMessage();
  private volatile MessageSnapshot current;

  public MessageService(JavaPlugin plugin) {
    this.plugin = plugin;
    reload();
  }

  public void reload() {
    swap(parseCandidate());
  }

  public MessageSnapshot parseCandidate() {
    File file = new File(plugin.getDataFolder(), "messages.yml");
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.load(file);
      YamlConfiguration defaults = new YamlConfiguration();
      try (var stream = plugin.getResource("messages.yml")) {
        if (stream != null) {
          defaults =
              YamlConfiguration.loadConfiguration(
                  new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
      }
      Map<String, String> templates = new HashMap<>();
      Map<String, Component> components = new HashMap<>();
      Map<String, Object> merged = new LinkedHashMap<>(defaults.getValues(true));
      merged.putAll(yaml.getValues(true));
      for (Map.Entry<String, Object> entry : merged.entrySet()) {
        if (!(entry.getValue() instanceof String raw)) continue;
        templates.put(entry.getKey(), raw);
        components.put(entry.getKey(), mini.deserialize(raw));
      }
      String prefixRaw =
          yaml.contains("prefix")
              ? yaml.getString("prefix", "")
              : defaults.getString("prefix", "");
      return new MessageSnapshot(
          prefixRaw, mini.deserialize(prefixRaw), templates, components);
    } catch (IOException
        | InvalidConfigurationException
        | IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "messages.yml no es válido: " + exception.getMessage(), exception);
    }
  }

  public void swap(MessageSnapshot candidate) {
    current = candidate;
  }

  public MessageSnapshot current() {
    return current;
  }

  public Component component(String key, Map<String, String> values) {
    MessageSnapshot snapshot = current;
    if (values.isEmpty()) {
      return snapshot.staticComponents().getOrDefault(
          key, Component.text("Mensaje ausente: " + key));
    }
    String raw = snapshot.templates().getOrDefault(key, "<red>Mensaje ausente: " + key);
    for (Map.Entry<String, String> entry : values.entrySet()) {
      raw = raw.replace("<" + entry.getKey() + ">", escape(entry.getValue()));
    }
    return mini.deserialize(raw);
  }

  public Component component(String key) {
    return component(key, Map.of());
  }

  public void send(CommandSender sender, String key) {
    sender.sendMessage(current.prefix().append(component(key)));
  }

  public void send(CommandSender sender, String key, Map<String, String> values) {
    sender.sendMessage(current.prefix().append(component(key, values)));
  }

  public Component parse(String raw) {
    return mini.deserialize(raw);
  }

  private String escape(String value) {
    return value.replace("<", "\\<");
  }
}
