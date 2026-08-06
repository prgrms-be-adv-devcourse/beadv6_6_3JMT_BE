package com.prompthub.order.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void created_allowsCompletedAndFailed() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.FAILED)).isTrue();
    }

    @Test
    void failed_allowsCompletedRetry() {
        assertThat(OrderStatus.FAILED.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
	void completed_allowsRefundRequested() {
		assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.REFUND_REQUESTED)).isTrue();
    }

    @Test
	void partialRefunded_allowsNextRefundRequest() {
		assertThat(OrderStatus.PARTIAL_REFUNDED.canTransitionTo(OrderStatus.REFUND_REQUESTED)).isTrue();
	}

	@Test
	void refundRequested_allowsPartialOrAllRefunded() {
		assertThat(OrderStatus.REFUND_REQUESTED.canTransitionTo(OrderStatus.PARTIAL_REFUNDED)).isTrue();
		assertThat(OrderStatus.REFUND_REQUESTED.canTransitionTo(OrderStatus.ALL_REFUNDED)).isTrue();
    }

    @Test
    void allRefunded_isTerminal() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.ALL_REFUNDED.canTransitionTo(target)).isFalse();
        }
    }
}
