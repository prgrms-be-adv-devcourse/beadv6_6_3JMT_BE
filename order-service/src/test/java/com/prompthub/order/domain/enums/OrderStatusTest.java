package com.prompthub.order.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void canTransitionTo_fromPending_shouldReturnTrueForAllowedTargets() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.FAILED)).isTrue();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELED)).isTrue();
    }

    @Test
    void canTransitionTo_fromPending_shouldReturnFalseForOthers() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.REFUNDED)).isFalse();
    }

    @Test
    void canTransitionTo_fromPaid_shouldReturnTrueForRefundRequestedAndRefunded() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.REFUND_REQUESTED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
    }

    @Test
    void canTransitionTo_fromPaid_shouldReturnFalseForOthers() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.FAILED)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELED)).isFalse();
    }

    @Test
    void canTransitionTo_fromFailed_shouldReturnTrueForPaid() {
        assertThat(OrderStatus.FAILED.canTransitionTo(OrderStatus.PAID)).isTrue();
    }

    @Test
    void canTransitionTo_fromRefundRequested_shouldReturnTrueForPartialRefundedAndRefunded() {
        assertThat(OrderStatus.REFUND_REQUESTED.canTransitionTo(OrderStatus.PARTIAL_REFUNDED)).isTrue();
        assertThat(OrderStatus.REFUND_REQUESTED.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
    }

    @Test
    void canTransitionTo_fromRefundRequested_shouldReturnFalseForOtherTargets() {
        for (OrderStatus target : OrderStatus.values()) {
            if (target != OrderStatus.PARTIAL_REFUNDED && target != OrderStatus.REFUNDED) {
                assertThat(OrderStatus.REFUND_REQUESTED.canTransitionTo(target)).isFalse();
            }
        }
    }

    @Test
    void canTransitionTo_fromPartialRefunded_shouldReturnTrueForRefundRequested() {
        assertThat(OrderStatus.PARTIAL_REFUNDED.canTransitionTo(OrderStatus.REFUND_REQUESTED)).isTrue();
    }

    @Test
    void canTransitionTo_fromPartialRefunded_shouldReturnFalseForOtherTargets() {
        for (OrderStatus target : OrderStatus.values()) {
            if (target != OrderStatus.REFUND_REQUESTED) {
                assertThat(OrderStatus.PARTIAL_REFUNDED.canTransitionTo(target)).isFalse();
            }
        }
    }

    @Test
    void canTransitionTo_fromFailed_shouldReturnFalseForOthers() {
        for (OrderStatus target : OrderStatus.values()) {
            if (target != OrderStatus.PAID) {
                assertThat(OrderStatus.FAILED.canTransitionTo(target)).isFalse();
            }
        }
    }

    @Test
    void canTransitionTo_fromCanceled_shouldAlwaysReturnFalse() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.CANCELED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void canTransitionTo_fromRefunded_shouldAlwaysReturnFalse() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.REFUNDED.canTransitionTo(target)).isFalse();
        }
    }
}
