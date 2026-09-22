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
import net.watones.novagems.shop.menu.QuantityMenuHolder;
import net.watones.novagems.shop.menu.ShopMenuHolder;
import net.watones.novagems.shop.menu.StackPickerHolder;
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
    RuntimeConfig runtime = runtimeConfig.current();
    long intervalMinutes = runtime.intervalSeconds() / 60;
    inventory.setItem(
        shop.layout().profileSlot(),
        playerHead(
            player,
            "<gold>" + player.getName(),
            List.of(
                "<gray>Podrás conseguir gemas de",
                "<gray>las siguientes maneras:",
                "",
                "<yellow>1.- <white>Cada <green>" + intervalMinutes + " minutos <white>podrás",
                "<white>conseguir <light_purple>" + Formatters.number(runtime.gemsPerInterval()) + " gemas",
                "<yellow>2.- <white>En cada eliminación podrás",
                "<white>conseguir <light_purple>"
                    + Formatters.number(runtime.killRewards().gemsPerKill()) + " gemas",
                "<yellow>3.- <white>Por cada misión completada <yellow>(/misiones)",
                "<white>podrás conseguir <light_purple>10 gemas",
                "",
                "<yellow>Saldo<dark_gray>: <light_purple>" + Formatters.number(balance) + " gemas")));
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
        named(Material.OAK_DOOR, "<red>Cerrar", List.of("<gray>Click para cerrar este menú"), true));
    if (shop.categorySelectorVisible()) {
      inventory.setItem(
          shop.layout().categorySlot(),
          named(
              Material.COMPASS,
              "<gold>Categoría: <white>" + categoryLabel(category),
              List.of("<gray>Click para cambiar")));
    }
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
    if (slot == snapshot.layout().infoSlot()) {
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
    if (reward.quantitySelectable())
      openQuantityMenu(player, reward, holder.page(), holder.category(), 1);
    else if (reward.confirmation())
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
    inventory.setItem(
        13,
        named(
            reward.icon(),
            replace(reward.name(), player, reward, balance),
            lore,
            reward.glow()));
    inventory.setItem(
        11,
        named(
            Material.RED_STAINED_GLASS_PANE,
            "<red>✕ Rechazar",
            List.of("<gray>Vuelve a la tienda"),
            true));
    inventory.setItem(
        15,
        named(
            Material.LIME_STAINED_GLASS_PANE,
            "<green>✓ Confirmar",
            List.of("<gray>Click para confirmar la compra"),
            true));
    fillEmpty(inventory);
    player.openInventory(inventory);
  }

  /** Max stacks selectable via the bulk-quantity menu, regardless of the material's stack size. */
  private static final int MAX_STACKS = 9;
  private static final String REFERENCE_QUANTITY_DELIMITER = "@";

  private int maxQuantity(ShopReward reward) {
    return reward.icon().getMaxStackSize() * MAX_STACKS;
  }

  private String encodeReference(ShopReward reward, int quantity) {
    return quantity == 1 ? reward.id() : reward.id() + REFERENCE_QUANTITY_DELIMITER + quantity;
  }

  private String rewardIdFromReference(String reference) {
    int at = reference.indexOf(REFERENCE_QUANTITY_DELIMITER);
    return at < 0 ? reference : reference.substring(0, at);
  }

  private int quantityFromReference(String reference) {
    int at = reference.indexOf(REFERENCE_QUANTITY_DELIMITER);
    if (at < 0) return 1;
    try {
      return Math.max(1, Integer.parseInt(reference.substring(at + 1)));
    } catch (NumberFormatException invalid) {
      return 1;
    }
  }

  public void openQuantityMenu(
      Player player, ShopReward reward, int page, String category, int requestedQuantity) {
    PurchaseResult preflight = preflight(player, reward);
    if (preflight != PurchaseResult.SUCCESS) {
      showResult(player, preflight, reward);
      return;
    }
    int quantity = Math.max(1, Math.min(requestedQuantity, maxQuantity(reward)));
    long balance = wallets.account(player.getUniqueId()).map(PlayerAccount::balance).orElse(0L);
    long totalPrice = totalPrice(reward, quantity);
    QuantityMenuHolder holder =
        new QuantityMenuHolder(reward.id(), config.current().version(), page, category, quantity);
    Inventory inventory =
        Bukkit.createInventory(holder, 27, messages.parse(config.current().confirmationTitle()));
    holder.inventory(inventory);
    List<String> lore = new java.util.ArrayList<>();
    reward.lore().stream().map(line -> replace(line, player, reward, balance)).forEach(lore::add);
    lore.add("");
    lore.add("<gray>Cantidad seleccionada: <white>x" + quantity);
    lore.add("<gray>Precio total: <light_purple>" + Formatters.number(totalPrice) + " gemas");
    lore.add("<gray>Saldo actual: <white>" + Formatters.number(balance));
    lore.add("");
    lore.add("<green>Click para elegir por stacks");
    inventory.setItem(
        13,
        named(
            reward.icon(),
            Math.min(64, quantity),
            "<green>Comprar por STACKS",
            lore,
            reward.glow()));
    Material redQuantity =
        reward.quantityPanels() ? Material.RED_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS;
    Material greenQuantity =
        reward.quantityPanels() ? Material.LIME_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS;
    inventory.setItem(10, named(redQuantity, "<red>- 10", List.of(), true));
    inventory.setItem(11, named(redQuantity, "<red>- 1", List.of(), true));
    inventory.setItem(
        12, named(redQuantity, "<red>Restablecer <gray>(x1)", List.of(), true));
    inventory.setItem(14, named(greenQuantity, "<green>+ 1", List.of(), true));
    inventory.setItem(15, named(greenQuantity, "<green>+ 10", List.of(), true));
    inventory.setItem(16, named(greenQuantity, "<green>+ 64", List.of(), true));
    inventory.setItem(20, named(Material.LIME_CONCRETE, "<green>✓ Confirmar", List.of()));
    inventory.setItem(
        24,
        named(Material.RED_CONCRETE, "<red>✕ Rechazar", List.of("<gray>Vuelve a la tienda")));
    fillEmpty(inventory);
    player.openInventory(inventory);
  }

  public void handleQuantityClick(Player player, QuantityMenuHolder holder, int slot) {
    if (MenuPosition.stale(holder.snapshotVersion(), config.current().version())) {
      open(player, holder.page(), holder.category());
      return;
    }
    ShopReward reward = config.current().rewards().get(holder.rewardId());
    if (reward == null) {
      showResult(player, PurchaseResult.INVALID_REWARD, null);
      return;
    }
    int max = maxQuantity(reward);
    int current = holder.quantity();
    switch (slot) {
      case 10 -> openQuantityMenu(
          player, reward, holder.page(), holder.category(), Math.max(1, current - 10));
      case 11 -> openQuantityMenu(
          player, reward, holder.page(), holder.category(), Math.max(1, current - 1));
      case 12 -> openQuantityMenu(player, reward, holder.page(), holder.category(), 1);
      case 13 -> openStackPicker(player, reward, holder.page(), holder.category(), current);
      case 14 -> openQuantityMenu(
          player, reward, holder.page(), holder.category(), Math.min(max, current + 1));
      case 15 -> openQuantityMenu(
          player, reward, holder.page(), holder.category(), Math.min(max, current + 10));
      case 16 -> openQuantityMenu(
          player, reward, holder.page(), holder.category(), Math.min(max, current + 64));
      case 20 -> purchase(player, reward.id(), holder.page(), holder.category(), current);
      case 24 -> open(player, holder.page(), holder.category());
      default -> {}
    }
  }

  public void openStackPicker(
      Player player, ShopReward reward, int page, String category, int currentQuantity) {
    int maxStack = reward.icon().getMaxStackSize();
    StackPickerHolder holder =
        new StackPickerHolder(reward.id(), config.current().version(), page, category);
    Inventory inventory =
        Bukkit.createInventory(holder, 27, messages.parse(config.current().confirmationTitle()));
    holder.inventory(inventory);
    for (int stacks = 1; stacks <= MAX_STACKS; stacks++) {
      int units = maxStack * stacks;
      long totalPrice = totalPrice(reward, units);
      boolean selected = units == currentQuantity;
      List<String> lore =
          List.of(
              "<gray>Cantidad: <white>x" + units,
              "<gray>Precio: <light_purple>" + Formatters.number(totalPrice) + " gemas",
              selected ? "<yellow>✔ Seleccionado" : "<green>Click para seleccionar");
      inventory.setItem(
          8 + stacks,
          named(reward.icon(), Math.min(64, units), "<green>Stack " + stacks, lore, selected));
    }
    fillEmpty(inventory);
    player.openInventory(inventory);
  }

  public void handleStackPick(Player player, StackPickerHolder holder, int slot) {
    if (MenuPosition.stale(holder.snapshotVersion(), config.current().version())) {
      open(player, holder.page(), holder.category());
      return;
    }
    if (slot < 9 || slot > 17) return;
    ShopReward reward = config.current().rewards().get(holder.rewardId());
    if (reward == null) {
      showResult(player, PurchaseResult.INVALID_REWARD, null);
      return;
    }
    int units = reward.icon().getMaxStackSize() * (slot - 8);
    openQuantityMenu(player, reward, holder.page(), holder.category(), units);
  }

  private long totalPrice(ShopReward reward, int quantity) {
    try {
      return Math.multiplyExact(reward.price(), (long) quantity);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  public void purchase(Player player, String rewardId) {
    purchase(player, rewardId, 1);
  }

  public void purchase(Player player, String rewardId, int page) {
    purchase(player, rewardId, page, "all");
  }

  public void purchase(Player player, String rewardId, int page, String category) {
    purchase(player, rewardId, page, category, 1);
  }

  public void purchase(
      Player player, String rewardId, int page, String category, int requestedQuantity) {
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
    int quantity =
        reward.quantitySelectable()
            ? Math.max(1, Math.min(requestedQuantity, maxQuantity(reward)))
            : 1;
    long totalPrice = totalPrice(reward, quantity);
    PurchaseResult preflight = preflight(player, reward, totalPrice, quantity);
    if (preflight != PurchaseResult.SUCCESS) {
      gate.unlock(player.getUniqueId());
      showResult(player, preflight, reward, totalPrice, page, category);
      return;
    }
    int processingSlot = reward.quantitySelectable() ? 20 : 15;
    player
        .getOpenInventory()
        .setItem(processingSlot, named(Material.CLOCK, "<yellow>Procesando…", List.of()));
    wallets
        .debitForDelivery(
            player.getUniqueId(),
            totalPrice,
            TransactionType.SHOP_PURCHASE,
            "SHOP_PURCHASE",
            encodeReference(reward, quantity),
            true)
        .whenComplete(
            (charged, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () ->
                            chargeCompleted(
                                player, reward, page, category, quantity, totalPrice, charged,
                                error)));
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
      int quantity,
      long chargedAmount,
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
      showResult(player, result, reward, chargedAmount, page, category);
      return;
    }
    if (!player.isOnline()) {
      gate.unlock(player.getUniqueId());
      plugin
          .getLogger()
          .info("Entrega " + charged.operationId() + " pendiente hasta la próxima conexión");
      return;
    }
    finishDelivery(player, reward, charged.operationId(), page, category, quantity, chargedAmount);
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
      String rewardId = rewardIdFromReference(operation.reference());
      ShopReward reward = config.current().rewards().get(rewardId);
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
      finishDelivery(
          player,
          reward,
          operation.operationId(),
          1,
          "all",
          quantityFromReference(operation.reference()),
          operation.amount());
    }
  }

  public boolean retryDelivery(UUID operationId) {
    var pending = wallets.pendingDelivery(operationId);
    if (pending.isEmpty()) return false;
    Player player = Bukkit.getPlayer(pending.get().accountId());
    if (player == null || !player.isOnline() || !gate.tryLock(player.getUniqueId())) return false;
    String rewardId = rewardIdFromReference(pending.get().reference());
    ShopReward reward = config.current().rewards().get(rewardId);
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
    finishDelivery(
        player,
        reward,
        operationId,
        1,
        "all",
        quantityFromReference(pending.get().reference()),
        pending.get().amount());
    return true;
  }

  private PurchaseResult preflight(Player player, ShopReward reward) {
    return preflight(player, reward, reward.price(), 1);
  }

  private PurchaseResult preflight(
      Player player, ShopReward reward, long totalPrice, int quantity) {
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
    boolean inventoryFits = inventoryFits(player, reward, quantity);
    PurchasePolicy.Decision decision =
        PurchasePolicy.validate(balance, totalPrice, inventoryFits);
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
      Player player,
      ShopReward reward,
      UUID operationId,
      int page,
      String category,
      int quantity,
      long chargedAmount) {
    if (operationId == null) {
      DeliveryOutcome outcome = deliver(player, reward, null, quantity);
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
                                  ? deliver(player, reward, operationId, quantity)
                                  : new DeliveryOutcome(
                                      PurchaseResult.DELIVERY_FAILED,
                                      true,
                                      "El jugador se desconectó antes de la entrega");
                          persistDelivery(player, reward, operationId, outcome, chargedAmount);
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
      Player player,
      ShopReward reward,
      UUID operationId,
      DeliveryOutcome outcome,
      long chargedAmount) {
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
                    chargedAmount,
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

  private DeliveryOutcome deliver(Player player, ShopReward reward, UUID operationId, int quantity) {
    boolean delivered = false;
    boolean irreversibleAttempted = false;
    try {
      for (RewardAction action : reward.actions()) {
        if (!(action instanceof RewardAction.Item item)) continue;
        ItemStack toGive = item.value().clone();
        if (reward.quantitySelectable()) toGive.setAmount(quantity);
        Map<Integer, ItemStack> left = player.getInventory().addItem(toGive);
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
        String commandText =
            command
                .value()
                .replace("<player>", player.getName())
                .replace(
                    "<operation_id>", operationId == null ? "free" : operationId.toString());
        if (reward.quantitySelectable()) {
          commandText = commandText.replace("<quantity>", Integer.toString(quantity));
        }
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandText);
        if (!accepted) throw new IllegalStateException("El comando de canje no fue aceptado");
        delivered = true;
      }
      for (RewardAction action : reward.actions()) {
        try {
          if (action instanceof RewardAction.Message message) {
            String text =
                reward.quantitySelectable()
                    ? message.value().replace("<quantity>", Integer.toString(quantity))
                    : message.value();
            player.sendMessage(messages.parse(text));
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
    showResult(
        player, result, reward, reward == null ? 0 : reward.price(), page, category);
  }

  private void showResult(
      Player player,
      PurchaseResult result,
      ShopReward reward,
      long displayPrice,
      int page,
      String category) {
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
        // Cierra el menú en vez de reabrir la tienda: obliga a reejecutar /gemas para la próxima
        // compra, evitando reabrir/reclickear sobre un inventario que se está refrescando.
        player.closeInventory();
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
              Map.of("price", reward == null ? "?" : Formatters.number(displayPrice)));
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
    } else if (!inventoryFits(player, reward, 1)) {
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

  private boolean inventoryFits(Player player, ShopReward reward, int quantity) {
    if (runtimeConfig.current().fullInventoryBehavior()
        == RuntimeConfig.FullInventoryBehavior.DROP) return true;
    List<ItemStack> items =
        reward.actions().stream()
            .filter(RewardAction.Item.class::isInstance)
            .map(RewardAction.Item.class::cast)
            .map(
                action -> {
                  ItemStack clone = action.value().clone();
                  if (reward.quantitySelectable()) clone.setAmount(quantity);
                  return clone;
                })
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
    return named(material, 1, name, lore, glow);
  }

  private ItemStack named(
      Material material, int amount, String name, List<String> lore, boolean glow) {
    ItemStack item = new ItemStack(material, amount);
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
