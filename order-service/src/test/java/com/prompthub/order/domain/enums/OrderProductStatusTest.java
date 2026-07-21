package com.prompthub.order.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderProductStatusTest {

    @Test
    void pending_allowsPaidAndFailed() {
        assertThat(OrderProductStatus.PENDING.canTransitionTo(OrderProductStatus.PAID)).isTrue();
        assertThat(OrderProductStatus.PENDING.canTransitionTo(OrderProductStatus.FAILED)).isTrue();
    }

    @Test
    void failed_allowsPaidRetry() {
        assertThat(OrderProductStatus.FAILED.canTransitionTo(OrderProductStatus.PAID)).isTrue();
    }

    @Test
	void paid_allowsRefundRequested() {
		assertThat(OrderProductStatus.PAID.canTransitionTo(OrderProductStatus.REFUND_REQUESTED)).isTrue();
	}

	@Test
	void refundRequested_allowsRefunded() {
		assertThat(OrderProductStatus.REFUND_REQUESTED.canTransitionTo(OrderProductStatus.REFUNDED)).isTrue();
    }

    @Test
    void refunded_isTerminal() {
        for (OrderProductStatus target : OrderProductStatus.values()) {
            assertThat(OrderProductStatus.REFUNDED.canTransitionTo(target)).isFalse();
        }
    }
}
