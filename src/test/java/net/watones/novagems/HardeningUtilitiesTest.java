package net.watones.novagems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.watones.novagems.economy.WalletService;
import net.watones.novagems.economy.DeliveryStateMachine;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.session.PlayerSession;
import net.watones.novagems.session.PendingCompletedRewards;
import net.watones.novagems.shop.ShopLayout;
import net.watones.novagems.shop.MenuPosition;
import net.watones.novagems.util.PageNumbers;
import org.junit.jupiter.api.Test;

class HardeningUtilitiesTest {
  @Test
  void exactDisconnectBoundariesProduceOnlyCompletedCycles() {
    long interval = Duration.ofMinutes(30).toNanos();
    assertThat(completed(interval - 1_000_000)).isZero();
    assertThat(completed(interval + 1_000_000)).isEqualTo(1);
    assertThat(completed(interval * 2 - 1_000_000)).isEqualTo(1);
    assertThat(completed(interval * 2 + 1_000_000)).isEqualTo(2);
  }

  @Test
  void completedRewardNeverReturnsToPlayerSession() {
    PlayerSession session = new PlayerSession(UUID.randomUUID(), 0);
    PendingCompletedRewards owner = new PendingCompletedRewards(1);
    var first = session.materializeCompleted(1, 10, Instant.EPOCH).getFirst();
    try (PendingCompletedRewards.Reservation reservation = owner.reserveAvailable()) {
      reservation.commit(List.of(first));
    }
    var claimed = owner.claim(1).getFirst();
    owner.retry(claimed.operationId());
    assertThat(owner.claim(1).getFirst().operationId()).isEqualTo(first.operationId());
    assertThat(PlayerSession.class.getDeclaredFields())
        .noneMatch(field -> field.getType().getSimpleName().contains("CompletedReward"));
  }

  @Test
  void pageParsingRejectsZeroNegativeAndHugeValues() {
    assertThat(PageNumbers.parse("1")).hasValue(1);
    assertThat(PageNumbers.parse("0")).isEmpty();
    assertThat(PageNumbers.parse("-1")).isEmpty();
    assertThat(PageNumbers.parse("2147483647")).isEmpty();
    assertThatThrownBy(() -> WalletService.safeOffset(Integer.MAX_VALUE, 10))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void layoutRejectsDuplicatesReservedOverlapAndInvalidSize() {
    assertThatThrownBy(() -> new ShopLayout(53, 4, 45, 49, 48, 50, 52, List.of(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShopLayout(54, 4, 45, 49, 48, 50, 53, List.of(4)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShopLayout(54, 4, 45, 49, 48, 50, 53, List.of(10, 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliveryStateMachineRejectsTerminalReplayAndAmbiguousRefund() {
    assertThat(
            DeliveryStateMachine.canTransition(
                TransactionStatus.DELIVERY_PENDING, TransactionStatus.DELIVERY_STARTED))
        .isTrue();
    assertThat(
            DeliveryStateMachine.canTransition(
                TransactionStatus.DELIVERY_STARTED, TransactionStatus.MANUAL_REVIEW))
        .isTrue();
    assertThat(
            DeliveryStateMachine.canTransition(
                TransactionStatus.DELIVERY_STARTED, TransactionStatus.REFUNDED))
        .isFalse();
    assertThat(
            DeliveryStateMachine.canTransition(
                TransactionStatus.DELIVERED, TransactionStatus.DELIVERY_STARTED))
        .isFalse();
  }

  @Test
  void staleGuiRefreshPreservesValidPageAndCategory() {
    assertThat(MenuPosition.stale(4, 5)).isTrue();
    MenuPosition retained =
        MenuPosition.resolve(3, "Llaves", List.of("llaves", "utilidad"), ignored -> 4);
    assertThat(retained).isEqualTo(new MenuPosition(3, "llaves"));
    MenuPosition clamped =
        MenuPosition.resolve(8, "removed", List.of("llaves"), ignored -> 2);
    assertThat(clamped).isEqualTo(new MenuPosition(2, "all"));
  }

  private int completed(long elapsed) {
    return new net.watones.novagems.session.SessionAccumulator(0)
        .update(elapsed, Duration.ofMinutes(30).toNanos(), false);
  }
}
