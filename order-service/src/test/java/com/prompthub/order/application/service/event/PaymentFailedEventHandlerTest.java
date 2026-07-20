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

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
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
	void handle_mapsPaymentServiceSingleOrderFailureAndDelegates() throws Exception {
		UUID eventId = UUID.randomUUID();
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_A, BUYER_ID));
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
		assertThat(captor.getValue().paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(captor.getValue().orderId()).isEqualTo(ORDER_A);
		assertThat(captor.getValue().buyerId()).isEqualTo(BUYER_ID);
		assertThat(captor.getValue().userId()).isEqualTo(BUYER_ID);
	}

	@Test
	void handle_invalidUuid_doesNotCallProcessor() throws Exception {
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "not-a-uuid",
			  "orderId": "%s",
			  "userId": "%s"
			}
			""".formatted(ORDER_A, BUYER_ID));
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
