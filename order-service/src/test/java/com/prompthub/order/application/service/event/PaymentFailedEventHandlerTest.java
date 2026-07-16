package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentFailedEventHandlerTest {

	private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
	private final EventPayloadMapper eventPayloadMapper = new EventPayloadMapper(objectMapper);

	@Mock
	private PaymentFailedProcessor processor;

	private PaymentFailedEventHandler handler;

	@BeforeEach
	void setUp() {
		handler = new PaymentFailedEventHandler(eventPayloadMapper, processor);
	}

	@Test
	void handle_mapsMultiOrderFailureAndDelegates() throws Exception {
		UUID eventId = UUID.randomUUID();
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "%s",
			  "orderIds": ["%s", "%s"],
			  "failureCode": "PAY_FAILED",
			  "failureReason": "PG 결제 실패",
			  "failedAt": "2026-07-16T10:00:06"
			}
			""".formatted(PAYMENT_ID, ORDER_A, ORDER_B));
		EventMessage<JsonNode> message = new EventMessage<>(
			eventId,
			"PAYMENT_FAILED",
			FAILED_AT,
			"PAYMENT",
			PAYMENT_ID,
			payload
		);

		handler.handle(message);

		ArgumentCaptor<PaymentFailedPayload> captor = ArgumentCaptor.forClass(PaymentFailedPayload.class);
		then(processor).should().process(eq(eventId), eq("PAYMENT_FAILED"), eq(FAILED_AT), captor.capture());
		assertThat(captor.getValue().orderIds()).containsExactly(ORDER_A, ORDER_B);
		assertThat(captor.getValue().failureCode()).isEqualTo("PAY_FAILED");
	}

	@Test
	void handle_invalidUuid_doesNotCallProcessor() throws Exception {
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "not-a-uuid",
			  "orderIds": [],
			  "failureCode": "PAY_FAILED",
			  "failureReason": "PG 결제 실패",
			  "failedAt": "2026-07-16T10:00:06"
			}
			""");
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			"PAYMENT",
			PAYMENT_ID,
			payload
		);

		assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(OrderException.class);
		then(processor).shouldHaveNoInteractions();
	}
}
