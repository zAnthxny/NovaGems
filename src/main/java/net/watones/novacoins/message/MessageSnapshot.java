package net.watones.novacoins.message;

import java.util.Map;
import net.kyori.adventure.text.Component;

public record MessageSnapshot(
    String prefixRaw,
    Component prefix,
    Map<String, String> templates,
    Map<String, Component> staticComponents) {
  public MessageSnapshot {
    templates = Map.copyOf(templates);
    staticComponents = Map.copyOf(staticComponents);
  }
}
