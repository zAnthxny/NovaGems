package net.watones.novagems.shop;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.format.TextDecoration;
import net.watones.novagems.config.ConfigManager;
import net.watones.novagems.config.RuntimeConfig;
import net.watones.novagems.config.ShopConfig;
import net.watones.novagems.economy.EconomyResult;
import net.watones.novagems.economy.PlayerAccount;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.message.MessageService;
import net.watones.novagems.session.SessionService;
import net.watones.novagems.shop.menu.ConfirmMenuHolder;
import net.watones.novagems.shop.menu.ShopMenuHolder;
import net.watones.novagems.util.Formatters;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopService {
  private static final int MAX_PENDING_NOTICES = 2048;
  private static final long PENDING_NOTICE_NANOS = java.time.Duration.ofSeconds(5).toNanos();
  private final JavaPlugin plugin;
  private final ShopConfig config;
  private final ConfigManager runtimeConfig;
  private final WalletService wallets;
  private final MessageService messages;
  private final SessionService sessions;
  private final PurchaseGate gate = new PurchaseGate();
  private final Map<UUID, Long> pendingNotices = new java.util.concurrent.ConcurrentHashMap<>();

  public ShopService(
      JavaPlugin plugin,
      ShopConfig config,
      ConfigManager runtimeConfig,
      WalletService wallets,
      MessageService messages,
      SessionService sessions) {
    this.plugin = plugin;
    this.config = config;
    this.runtimeConfig = runtimeConfig;
    this.wallets = wallets;
    this.messages = messages;
    this.sessions = sessions;
    wallets.onDeliveryReady(
        uuid -> {
          if (!plugin.isEnabled()) return;
          Bukkit.getScheduler()
              .runTask(
                  plugin,
                  () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) recoverPending(player);
                  });
        });
  }

  public int rewardCount() {
    return config.current().rewards().size();
  }

  public long version() { return config.current().version(); }

  public void open(Player player) {
    open(player, 1);
  }

  public void open(Player player, int page) {
    open(player, page, "all");
  }

  public void open(Player player, int page, String requestedCategory) {
    if (wallets.account(player.getUniqueId()).isEmpty()) {
      messages.send(player, "account-loading");
      return;
    }
    ShopSnapshot shop = config.current();
    if (shop.rewards().isEmpty()) {
      messages.send(player, "shop-empty");
      return;
    }
    MenuPosition position =
        MenuPosition.resolve(page, requestedCategory, shop.categories(), shop::pageCount);
    String category = position.category();
    int pageCount = shop.pageCount(category);
    int selectedPage = position.page();
    ShopMenuHolder holder = new ShopMenuHolder(shop.version(), selectedPage, category);
    Inventory inventory =
        Bukkit.createInventory(holder, shop.size(), messages.parse(shop.title()));
    holder.inventory(inventory);
    long balance = wallets.account(player.getUniqueId()).map(PlayerAccount::balance).orElse(0L);
    inventory.setItem(
        shop.layout().profileSlot(),
        playerHead(
            player,
            "<gold>" + player.getName(),
            List.of(
                "<gray>Saldo",
                "<white>" + Formatters.number(balance) + " gemas")));
    for (ShopReward reward : shop.rewards().values()) {
      if (reward.page() == selectedPage
          && ("all".equals(category) || reward.category().equalsIgnoreCase(category)))
        inventory.setItem(reward.slot(), icon(player, reward, balance));
    }
    if (selectedPage > 1)
      inventory.setItem(
          shop.layout().previousSlot(), named(Material.ARROW, "<yellow>Página anterior", List.of()));
    inventory.setItem(
        shop.layout().infoSlot(),
        named(
            Material.BOOK,
            "<gold>Información",
            List.of(
                "<gray>Página <white>" + selectedPage + "<gray>/<white>" + pageCount,
                "<gray>Saldo: <white>" + Formatters.number(balance))));
    if (shop.categorySelectorVisible()) {
      inventory.setItem(
          shop.layout().categorySlot(),
          named(
              Material.COMPASS,
              "<gold>Categoría: <white>" + categoryLabel(category),
              List.of("<gray>Click para cambiar")));
    }
    inventory.setItem(
        shop.layout().closeSlot(), named(Material.BARRIER, "<red>Cerrar", List.of()));
    if (selectedPage < pageCount)
      inventory.setItem(
          shop.layout().nextSlot(), named(Material.ARROW, "<yellow>Página siguiente", List.of()));
    fillEmpty(inventory);
    player.openInventory(inventory);
  }

  public void select(Player player, ShopMenuHolder holder, int slot) {
    if (MenuPosition.stale(holder.snapshotVersion(), config.current().version())) {
      open(player, holder.page(), holder.category());
      return;
    }
    ShopSnapshot snapshot = config.current();
    if (slot == snapshot.layout().previousSlot() && holder.page() > 1) {
      open(player, holder.page() - 1, holder.category());
      return;
    }
    if (slot == snapshot.layout().closeSlot()) {
      player.closeInventory();
      return;
    }
    if (slot == snapshot.layout().categorySlot() && snapshot.categorySelectorVisible()) {
      open(player, 1, nextCategory(snapshot, holder.category()));
      return;
    }
    if (slot == snapshot.layout().nextSlot()
        && holder.page() < snapshot.pageCount(holder.category())) {
      open(player, holder.page() + 1, holder.category());
      return;
    }
    ShopReward reward =
        config.current().rewards().values().stream()
            .filter(
                candidate ->
                    candidate.page() == holder.page()
                        && candidate.slot() == slot
                        && ("all".equals(holder.category())
                            || candidate.category().equalsIgnoreCase(holder.category())))
            .findFirst()
            .orElse(null);
    if (reward == null) return;
    playUiSound(player, runtimeConfig.current().shopSounds().click());
    if (reward.confirmation())
      openConfirmation(player, reward, holder.page(), holder.category());
    else purchase(player, reward.id(), holder.page(), holder.category());
  }

  public void openConfirmation(Player player, ShopReward reward, int page) {
    openConfirmation(player, reward, page, "all");
  }

  public void openConfirmation(Player player, ShopReward reward, int page, String category) {
    PurchaseResult preflight = preflight(player, reward);
    if (preflight != PurchaseResult.SUCCESS) {
      showResult(player, preflight, reward);
      return;
    }
    ConfirmMenuHolder holder =
        new ConfirmMenuHolder(reward.id(), config.current().version(), page, category);
    Inventory inventory =
        Bukkit.createInventory(holder, 27, messages.parse(config.current().confirmationTitle()));
    holder.inventory(inventory);
    long balance = wallets.account(player.getUniqueId()).map(PlayerAccount::balance).orElse(0L);
    List<String> lore = new java.util.ArrayList<>();
    reward.lore().stream().map(line -> replace(line, player, reward, balance)).forEach(lore::add);
    lore.add("");
    lore.add("<gray>Saldo actual: <white>" + Formatters.number(balance));
    lore.add(
        "<gray>Saldo después: <white>"
            + Formatters.number(Math.max(0, balance - reward.price())));
    lore.add("");
    lore.add("<green>✓ Click para confirmar la compra");
    inventory.setItem(
        13,
        named(
            reward.icon(),
            replace(reward.name(), player, reward, balance),
            lore,
            reward.glow()));
    inventory.setItem(
        11, named(Material.RED_CONCRETE, "<red>Cancelar", List.of("<gray>Vuelve a la tienda")));
    fillEmpty(inventory);
    player.openInventory(inventory);
  }

  public void purchase(Player player, String rewardId) {
    purchase(player, rewardId, 1);
  }

  public void purchase(Player player, String rewardId, int page) {
    purchase(player, rewardId, page, "all");
  }

  public void purchase(Player player, String rewardId, int page, String category) {
    if (wallets.hasPendingPurchase(player.getUniqueId())) {
      showResult(player, PurchaseResult.PENDING_CONFIRMATION, null, page, category);
      return;
    }
    if (!gate.tryLock(player.getUniqueId())) {
      messages.send(player, "purchase-busy");
      return;
    }
    ShopReward reward = config.current().rewards().get(rewardId);
    if (reward == null) {
      gate.unlock(player.getUniqueId());
      showResult(player, PurchaseResult.INVALID_REWARD, null);
      return;
    }
    PurchaseResult preflight = preflight(player, reward);
    if (preflight != PurchaseResult.SUCCESS) {
      gate.unlock(player.getUniqueId());
      showResult(player, preflight, reward);
      return;
    }
    player
        .getOpenInventory()
        .setItem(15, named(Material.CLOCK, "<yellow>Procesando…", List.of()));
    wallets
        .debitForDelivery(
            player.getUniqueId(),
            reward.price(),
            TransactionType.SHOP_PURCHASE,
            "SHOP_PURCHASE",
            reward.id(),
            true)
        .whenComplete(
            (charged, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> chargeCompleted(player, reward, page, category, charged, error)));
  }

  public void confirm(Player player, ConfirmMenuHolder holder) {
    if (MenuPosition.stale(holder.snapshotVersion(), config.current().version())) {
      open(player, holder.page(), holder.category());
      return;
    }
    purchase(player, holder.rewardId(), holder.page(), holder.category());
  }

  private void chargeCompleted(
      Player player,
      ShopReward reward,
      int page,
      String category,
      EconomyResult charged,
      Throwable error) {
    if (error != null || charged == null || !charged.success()) {
      gate.unlock(player.getUniqueId());
      if (charged != null && charged.recoveryPending()) {
        showResult(player, PurchaseResult.PENDING_CONFIRMATION, reward, page, category);
        return;
      }
      PurchaseResult result =
          charged != null && charged.status() == EconomyResult.Status.INSUFFICIENT_FUNDS
              ? PurchaseResult.INSUFFICIENT_FUNDS
              : PurchaseResult.DELIVERY_FAILED;
      showResult(player, result, reward, page, category);
      return;
    }
    if (!player.isOnline()) {
      gate.unlock(player.getUniqueId());
      plugin
          .getLogger()
          .info("Entrega " + charged.operationId() + " pendiente hasta la próxima conexión");
      return;
    }
    finishDelivery(player, reward, charged.operationId(), page, category);
  }

  public void recoverPending(Player player) {
    for (var operation : wallets.pendingDeliveries(player.getUniqueId())) {
      TransactionStatus status =
          wallets
              .pendingDeliveryStatus(operation.operationId())
              .orElse(TransactionStatus.DELIVERY_PENDING);
      if (status.needsSafeRefund()) {
        continueSafeRefund(player, operation, "DELIVERY_FAILURE_CONFIRMED");
        continue;
      }
      if (status != TransactionStatus.DELIVERY_PENDING) continue;
      ShopReward reward = config.current().rewards().get(operation.reference());
      if (reward == null) {
        wallets
            .markDelivery(
                operation.operationId(),
                TransactionStatus.DELIVERY_FAILED_SAFE,
                "El canje ya no existe")
            .thenRun(() -> continueSafeRefund(player, operation, "RECOVERY_REWARD_REMOVED"))
            .exceptionally(
                error -> {
                  logDeliveryAuditFailure(operation.operationId(), error);
                  return null;
                });
        continue;
      }
      if (!gate.tryLock(player.getUniqueId())) return;
      finishDelivery(player, reward, operation.operationId(), 1, "all");
    }
  }

  public boolean retryDelivery(UUID operationId) {
    var pending = wallets.pendingDelivery(operationId);
    if (pending.isEmpty()) return false;
    Player player = Bukkit.getPlayer(pending.get().accountId());
    if (player == null || !player.isOnline() || !gate.tryLock(player.getUniqueId())) return false;
    ShopReward reward = config.current().rewards().get(pending.get().reference());
    if (reward == null) {
      gate.unlock(player.getUniqueId());
      recoverPending(player);
      return true;
    }
    TransactionStatus status =
        wallets.pendingDeliveryStatus(operationId).orElse(TransactionStatus.DELIVERY_PENDING);
    if (status != TransactionStatus.DELIVERY_PENDING) {
      gate.unlock(player.getUniqueId());
      return false;
    }
    finishDelivery(player, reward, operationId, 1, "all");
    return true;
  }

  private PurchaseResult preflight(Player player, ShopReward reward) {
    if (wallets.pendingAdministrativeOperation(player.getUniqueId()).isPresent()) {
      return PurchaseResult.ACCOUNT_MUTATION_PENDING;
    }
    if (wallets.health() != net.watones.novagems.storage.StorageHealth.HEALTHY) {
      return PurchaseResult.DATABASE_UNAVAILABLE;
    }
    if (wallets.journalHealth() != net.watones.novagems.storage.JournalHealth.HEALTHY) {
      return PurchaseResult.JOURNAL_PROTECTED;
    }
    if (!wallets.canAcceptPurchase(player.getUniqueId())) {
      return PurchaseResult.JOURNAL_PROTECTED;
    }
    if (wallets.hasPendingPurchase(player.getUniqueId())) {
      return PurchaseResult.PENDING_CONFIRMATION;
    }
    long balance = wallets.account(player.getUniqueId()).map(PlayerAccount::balance).orElse(-1L);
    boolean inventoryFits = inventoryFits(player, reward);
    PurchasePolicy.Decision decision =
        PurchasePolicy.validate(balance, reward.price(), inventoryFits);
    if (decision == PurchasePolicy.Decision.INSUFFICIENT_FUNDS) {
      return balance < 0 ? PurchaseResult.ACCOUNT_NOT_READY : PurchaseResult.INSUFFICIENT_FUNDS;
    }
    if (decision == PurchasePolicy.Decision.INVENTORY_FULL) {
      return PurchaseResult.INVENTORY_FULL;
    }
    return decision == PurchasePolicy.Decision.ALLOW
        ? PurchaseResult.SUCCESS
        : PurchaseResult.INVALID_REWARD;
  }

  private void finishDelivery(
      Player player, ShopReward reward, UUID operationId, int page, String category) {
    if (operationId == null) {
      DeliveryOutcome outcome = deliver(player, reward, null);
      gate.unlock(player.getUniqueId());
      showResult(player, outcome.result(), reward, page, category);
      return;
    }
    wallets
        .markDelivery(operationId, TransactionStatus.DELIVERY_STARTED, "Entrega iniciada")
        .whenComplete(
            (ignored, startError) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (startError != null) {
                            gate.unlock(player.getUniqueId());
                            showResult(
                                player,
                                PurchaseResult.PENDING_CONFIRMATION,
                                reward,
                                page,
                                category);
                            logDeliveryAuditFailure(operationId, startError);
                            if (player.isOnline()) recoverPending(player);
                            return;
                          }
                          DeliveryOutcome outcome =
                              player.isOnline()
                                  ? deliver(player, reward, operationId)
                                  : new DeliveryOutcome(
                                      PurchaseResult.DELIVERY_FAILED,
                                      true,
                                      "El jugador se desconectó antes de la entrega");
                          persistDelivery(player, reward, operationId, outcome);
                          gate.unlock(player.getUniqueId());
                          PurchaseResult visible =
                              outcome.result() == PurchaseResult.DELIVERY_FAILED
                                      && !outcome.refundable()
                                  ? PurchaseResult.MANUAL_REVIEW
                                  : outcome.result();
                          showResult(player, visible, reward, page, category);
                          // Another purchase may still be queued behind this player's single
                          // delivery gate (e.g. several purchases piled up while storage was
                          // degraded); drain it now instead of waiting for the next periodic
                          // recovery pass.
                          if (player.isOnline()) recoverPending(player);
                        }));
  }

  private void persistDelivery(
      Player player, ShopReward reward, UUID operationId, DeliveryOutcome outcome) {
    UUID playerId = player.getUniqueId();
    if (outcome.result() == PurchaseResult.SUCCESS) {
      wallets
          .markDelivery(operationId, TransactionStatus.DELIVERED, null)
          .whenComplete((ignored, error) -> logDeliveryAuditFailure(operationId, error));
      return;
    }
    if (!outcome.refundable()) {
      wallets
          .markDelivery(operationId, TransactionStatus.MANUAL_REVIEW, outcome.error())
          .whenComplete((ignored, error) -> logDeliveryAuditFailure(operationId, error));
      return;
    }
    wallets
        .markDelivery(operationId, TransactionStatus.DELIVERY_FAILED_SAFE, outcome.error())
        .thenCompose(
            ignored ->
                wallets.refundPurchase(
                    playerId,
                    reward.price(),
                    "DELIVERY_FAILURE_CONFIRMED",
                    operationId))
        .thenCompose(
            refund -> {
              if (!refund.success()) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("No se pudo confirmar el reembolso"));
              }
              return wallets.markDelivery(operationId, TransactionStatus.REFUNDED, outcome.error());
            })
        .whenComplete((ignored, error) -> logDeliveryAuditFailure(operationId, error));
  }

  private void continueSafeRefund(
      Player player,
      net.watones.novagems.economy.EconomyOperation operation,
      String reason) {
    wallets
        .refundPurchase(player.getUniqueId(), operation.amount(), reason, operation.operationId())
        .thenCompose(
            refund ->
                refund.success()
                    ? wallets.markDelivery(
                        operation.operationId(), TransactionStatus.REFUNDED, reason)
                    : java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("No se pudo confirmar el reembolso")))
        .whenComplete((ignored, error) -> logDeliveryAuditFailure(operation.operationId(), error));
  }

  private DeliveryOutcome deliver(Player player, ShopReward reward, UUID operationId) {
    boolean delivered = false;
    boolean irreversibleAttempted = false;
    try {
      for (RewardAction action : reward.actions()) {
        if (!(action instanceof RewardAction.Item item)) continue;
        Map<Integer, ItemStack> left = player.getInventory().addItem(item.value().clone());
        delivered = true;
        if (!left.isEmpty()) {
          if (runtimeConfig.current().fullInventoryBehavior()
              == RuntimeConfig.FullInventoryBehavior.DROP) {
            left.values()
                .forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
          } else {
            throw new IllegalStateException("El inventario cambió durante la entrega");
          }
        }
      }
      for (RewardAction action : reward.actions()) {
        if (!(action instanceof RewardAction.Command command)) continue;
        irreversibleAttempted = true;
        boolean accepted =
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                command
                    .value()
                    .replace("<player>", player.getName())
                    .replace(
                        "<operation_id>", operationId == null ? "free" : operationId.toString()));
        if (!accepted) throw new IllegalStateException("El comando de canje no fue aceptado");
        delivered = true;
      }
      for (RewardAction action : reward.actions()) {
        try {
          if (action instanceof RewardAction.Message message) {
            player.sendMessage(messages.parse(message.value()));
          } else if (action instanceof RewardAction.Sound sound) {
            player.playSound(player.getLocation(), sound.value(), sound.volume(), sound.pitch());
          }
        } catch (RuntimeException cosmeticFailure) {
          plugin
              .getLogger()
              .log(
                  Level.WARNING,
                  "Falló una notificación del canje " + reward.id(),
                  cosmeticFailure);
        }
      }
      return new DeliveryOutcome(PurchaseResult.SUCCESS, false, null);
    } catch (Exception exception) {
      plugin
          .getLogger()
          .log(
              Level.SEVERE,
              "Fallo entregando el canje " + reward.id() + " a " + player.getName(),
              exception);
      String error =
          exception.getMessage() == null
              ? exception.getClass().getSimpleName()
              : exception.getMessage();
      return new DeliveryOutcome(
          PurchaseResult.DELIVERY_FAILED, !delivered && !irreversibleAttempted, error);
    }
  }

  private void logDeliveryAuditFailure(UUID operationId, Throwable error) {
    if (error != null) {
      plugin
          .getLogger()
          .log(Level.SEVERE, "No se pudo actualizar el estado de entrega " + operationId, error);
    }
  }

  private void showResult(Player player, PurchaseResult result, ShopReward reward) {
    showResult(player, result, reward, 1);
  }

  private void showResult(Player player, PurchaseResult result, ShopReward reward, int page) {
    showResult(player, result, reward, page, "all");
  }

  private void showResult(
      Player player, PurchaseResult result, ShopReward reward, int page, String category) {
    switch (result) {
      case SUCCESS -> {
        pendingNotices.remove(player.getUniqueId());
        playUiSound(player, runtimeConfig.current().shopSounds().success());
        messages.send(
            player,
            "purchase-success",
            Map.of(
                "balance",
                Formatters.number(
                    wallets.account(player.getUniqueId()).map(PlayerAccount::balance).orElse(0L))));
        open(player, page, category);
      }
      case BUSY -> {
        playUiSound(player, runtimeConfig.current().shopSounds().failure());
        messages.send(player, "purchase-busy");
      }
      case ACCOUNT_NOT_READY -> {
        playUiSound(player, runtimeConfig.current().shopSounds().failure());
        messages.send(player, "account-loading");
      }
      case ACCOUNT_MUTATION_PENDING -> {
        playUiSound(player, runtimeConfig.current().shopSounds().failure());
        messages.send(player, "account-mutation-pending");
      }
      case INSUFFICIENT_FUNDS -> {
          playUiSound(player, runtimeConfig.current().shopSounds().failure());
          messages.send(
              player,
              "insufficient-funds",
              Map.of("price", reward == null ? "?" : Formatters.number(reward.price())));
      }
      case INVENTORY_FULL -> {
        playUiSound(player, runtimeConfig.current().shopSounds().failure());
        messages.send(player, "inventory-full");
      }
      case DELIVERY_FAILED -> {
        playUiSound(player, runtimeConfig.current().shopSounds().failure());
        messages.send(player, "reward-delivery-failed");
      }
      case PENDING_CONFIRMATION -> {
        long now = System.nanoTime();
        if (pendingNotices.size() >= MAX_PENDING_NOTICES) {
          pendingNotices.entrySet().removeIf(
              entry -> now - entry.getValue() >= PENDING_NOTICE_NANOS);
          if (pendingNotices.size() >= MAX_PENDING_NOTICES
              && !pendingNotices.containsKey(player.getUniqueId())) {
            var iterator = pendingNotices.keySet().iterator();
            if (iterator.hasNext()) pendingNotices.remove(iterator.next());
          }
        }
        Long previous = pendingNotices.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= PENDING_NOTICE_NANOS) {
          messages.send(player, "purchase-pending");
        }
      }
      case MANUAL_REVIEW -> {
        pendingNotices.remove(player.getUniqueId());
        messages.send(player, "purchase-manual-review");
      }
      case DATABASE_UNAVAILABLE -> messages.send(player, "database-unavailable");
      case JOURNAL_PROTECTED -> messages.send(player, "journal-protected");
      case INVALID_REWARD -> messages.send(player, "shop-empty");
    }
  }

  private ItemStack icon(Player player, ShopReward reward, long balance) {
    List<String> lore = new java.util.ArrayList<>();
    reward.lore().stream().map(line -> replace(line, player, reward, balance)).forEach(lore::add);
    lore.add("");
    if (wallets.hasPendingPurchase(player.getUniqueId())) {
      lore.add("<yellow>⌛ Tienes un canje procesándose");
    } else if (!inventoryFits(player, reward)) {
      lore.add("<red>✕ Libera espacio en tu inventario");
    } else if (balance >= reward.price()) {
      lore.add("<green>✓ Disponible");
      lore.add("<gray>Click para canjear");
    } else {
      lore.add("<red>✕ Te faltan " + Formatters.number(reward.price() - balance) + " gemas");
    }
    return named(
        reward.icon(),
        replace(reward.name(), player, reward, balance),
        lore,
        reward.glow());
  }

  private boolean inventoryFits(Player player, ShopReward reward) {
    if (runtimeConfig.current().fullInventoryBehavior()
        == RuntimeConfig.FullInventoryBehavior.DROP) return true;
    List<ItemStack> items =
        reward.actions().stream()
            .filter(RewardAction.Item.class::isInstance)
            .map(RewardAction.Item.class::cast)
            .map(action -> action.value().clone())
            .toList();
    return InventoryCapacity.canFit(player.getInventory(), items);
  }

  private String replace(String text, Player player, ShopReward reward, long balance) {
    long missing = Math.max(0, reward.price() - balance);
    long after = Math.max(0, balance - reward.price());
    return text.replace("<price>", Formatters.number(reward.price()))
        .replace("<balance>", Formatters.number(balance))
        .replace("<missing>", Formatters.number(missing))
        .replace("<balance_after>", Formatters.number(after))
        .replace("<reward>", reward.name())
        .replace("<player>", player.getName());
  }

  private String nextCategory(ShopSnapshot snapshot, String current) {
    List<String> categories = new java.util.ArrayList<>();
    categories.add("all");
    categories.addAll(snapshot.categories());
    int index = categories.indexOf(current);
    return categories.get((index + 1 + categories.size()) % categories.size());
  }

  private String categoryLabel(String category) {
    if ("all".equals(category)) return "Todos";
    return Character.toUpperCase(category.charAt(0)) + category.substring(1);
  }

  private ItemStack playerHead(Player player, String name, List<String> lore) {
    ItemStack item = named(Material.PLAYER_HEAD, name, lore);
    if (item.getItemMeta() instanceof SkullMeta skull) {
      skull.setOwningPlayer(player);
      item.setItemMeta(skull);
    }
    return item;
  }

  private ItemStack named(Material material, String name, List<String> lore) {
    return named(material, name, lore, false);
  }

  private ItemStack named(Material material, String name, List<String> lore, boolean glow) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(messages.parse(name).decoration(TextDecoration.ITALIC, false));
    meta.lore(lore.stream()
        .map(messages::parse)
        .map(component -> component.decoration(TextDecoration.ITALIC, false))
        .toList());
    if (glow) {
      meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
      meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
    }
    item.setItemMeta(meta);
    return item;
  }

  private void fillEmpty(Inventory inventory) {
    ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
    }
  }

  private void playUiSound(Player player, org.bukkit.Sound sound) {
    RuntimeConfig.ShopSounds settings = runtimeConfig.current().shopSounds();
    if (settings.enabled()) {
      player.playSound(player.getLocation(), sound, settings.volume(), settings.pitch());
    }
  }

  private record DeliveryOutcome(PurchaseResult result, boolean refundable, String error) {}
}
