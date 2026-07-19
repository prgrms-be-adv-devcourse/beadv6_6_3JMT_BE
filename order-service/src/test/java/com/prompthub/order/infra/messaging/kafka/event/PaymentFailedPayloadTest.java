package com.prompthub.order.infra.messaging.kafka.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentFailedPayloadTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	@DisplayName("현재 생산자의 userId를 buyerId로 역직렬화하고 failedAt 누락 시 envelope 시각을 사용한다")
	void deserializesLegacyUserIdAndFallsBackToOccurredAt() throws Exception {
		String json = """
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_A, BUYER_ID);
		LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 18, 10, 0);

		PaymentFailedPayload payload = objectMapper.readValue(json, PaymentFailedPayload.class);

		assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
		assertThat(payload.userId()).isEqualTo(BUYER_ID);
		assertThat(payload.failureCode()).isNull();
		assertThat(payload.failureReason()).isNull();
		assertThat(payload.failedAtOr(occurredAt)).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("failedAt 오프셋 시각을 KST LocalDateTime으로 정규화한다")
	void normalizesOffsetFailedAtToKoreanLocalDateTime() {
		PaymentFailedPayload payload = new PaymentFailedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			"PAYMENT_REJECTED",
			"카드 승인 거절",
			"2026-07-18T01:00:05Z"
		);

		assertThat(payload.failedAtOr(LocalDateTime.MIN))
			.isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 5));
	}

	@Test
	@DisplayName("하위 호환 local 시각도 그대로 수용한다")
	void acceptsLocalFailedAt() {
		assertThat(PaymentEventTimeParser.parseRequired("2026-07-18T10:00:05"))
			.isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 5));
	}

	@Test
	@DisplayName("필수 시각이 비어 있으면 명시적인 예외를 발생시킨다")
	void parseRequiredRejectsBlankValue() {
		assertThatThrownBy(() -> PaymentEventTimeParser.parseRequired(" "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payment event time is required");
	}
}
