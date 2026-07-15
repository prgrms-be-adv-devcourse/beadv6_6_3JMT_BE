package com.prompthub.order.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

	@Test
	void from_refundRequestedAndPartialRefunded_returnsPaid() {
		assertThat(PaymentStatus.from(OrderStatus.REFUND_REQUESTED)).isEqualTo(PaymentStatus.PAID);
		assertThat(PaymentStatus.from(OrderStatus.PARTIAL_REFUNDED)).isEqualTo(PaymentStatus.PAID);
	}
}
