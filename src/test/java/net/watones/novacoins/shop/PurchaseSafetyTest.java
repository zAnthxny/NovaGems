package net.watones.novacoins.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PurchaseSafetyTest {
  @Test
  void actionsHaveExplicitCompensationSafety() {
    assertThat(new RewardAction.Command("grant <player>").safety())
        .isEqualTo(ActionSafety.IRREVERSIBLE);
    assertThat(new RewardAction.Message("done").safety()).isEqualTo(ActionSafety.COSMETIC);
  }

  @Test
  void multipleIrreversibleActionsRequireExplicitOverride() {
    assertThat(RewardSafety.irreversibleCountAllowed(1, false)).isTrue();
    assertThat(RewardSafety.irreversibleCountAllowed(2, false)).isFalse();
    assertThat(RewardSafety.irreversibleCountAllowed(2, true)).isTrue();
  }

  @Test
  void validatesBalanceAndInventoryBeforeCharge() {
    assertThat(PurchasePolicy.validate(100, 100, true)).isEqualTo(PurchasePolicy.Decision.ALLOW);
    assertThat(PurchasePolicy.validate(50, 100, true))
        .isEqualTo(PurchasePolicy.Decision.INSUFFICIENT_FUNDS);
    assertThat(PurchasePolicy.validate(100, 100, false))
        .isEqualTo(PurchasePolicy.Decision.INVENTORY_FULL);
  }

  @Test
  void cancelAndCloseNeverCharge() {
    assertThat(PurchasePolicy.shouldCharge(true, PurchasePolicy.ConfirmationChoice.CANCEL))
        .isFalse();
    assertThat(PurchasePolicy.shouldCharge(true, PurchasePolicy.ConfirmationChoice.CLOSE))
        .isFalse();
    assertThat(PurchasePolicy.shouldCharge(true, PurchasePolicy.ConfirmationChoice.CONFIRM))
        .isTrue();
  }

  @Test
  void concurrentDoublePurchaseRunsDeliveryOnce() throws Exception {
    PurchaseGate gate = new PurchaseGate();
    UUID uuid = UUID.randomUUID();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger deliveries = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Callable<String> first =
        () ->
            gate.run(
                uuid,
                () -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  deliveries.incrementAndGet();
                  return "ok";
                },
                () -> "busy");
    Future<String> a = pool.submit(first);
    entered.await(2, TimeUnit.SECONDS);
    Future<String> b = pool.submit(first);
    assertThat(b.get(2, TimeUnit.SECONDS)).isEqualTo("busy");
    release.countDown();
    assertThat(a.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
    assertThat(deliveries).hasValue(1);
    pool.shutdownNow();
  }
}
