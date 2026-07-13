package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.order.infra.messaging.kafka.event.PaymentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventTypeTest {

	@Test
	@DisplayName("TC-EVENTTYPE-001: 지원하는 eventType은 enum으로 변환된다")
	void from_returns_enum_when_supported() {
		assertThat(PaymentEventType.from("PAYMENT_APPROVED")).isEqualTo(PaymentEventType.PAYMENT_APPROVED);
		assertThat(PaymentEventType.from("PAYMENT_REFUNDED")).isEqualTo(PaymentEventType.PAYMENT_REFUNDED);
	}

	@Test
	@DisplayName("TC-EVENTTYPE-002: 미지원 eventType은 null을 반환한다")
	void from_returns_null_when_unsupported() {
		assertThat(PaymentEventType.from("PAYMENT_CHARGEBACK")).isNull();
		assertThat(PaymentEventType.from("ORDER_PAID")).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   "})
	@DisplayName("TC-EVENTTYPE-003: null 또는 blank eventType은 null을 반환한다")
	void from_returns_null_when_blank_or_null(String value) {
		assertThat(PaymentEventType.from(value)).isNull();
	}
}
