package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
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

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedEventHandlerTest {

	private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
	private final EventPayloadMapper eventPayloadMapper = new EventPayloadMapper(objectMapper);

	@Mock
	private PaymentApprovedProcessor processor;

	private PaymentApprovedEventHandler handler;

	@BeforeEach
	void setUp() {
		handler = new PaymentApprovedEventHandler(eventPayloadMapper, processor);
	}

	@Test
	void handle_mapsPaymentServiceSingleOrderApprovalAndDelegates() throws Exception {
		UUID eventId = UUID.randomUUID();
		String payloadJson = """
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "approvedAt": "2026-07-17T10:00:05+09:00"
			}
			""".formatted(PAYMENT_ID, ORDER_A, BUYER_ID);
		JsonNode payloadNode = objectMapper.readTree(payloadJson);
		EventMessage<JsonNode> message = new EventMessage<>(
			eventId,
			"PAYMENT_APPROVED",
			APPROVED_AT,
			"PAYMENT",
			PAYMENT_ID,
			payloadNode
		);

		handler.handle(message);

		ArgumentCaptor<PaymentApprovedPayload> captor = ArgumentCaptor.forClass(PaymentApprovedPayload.class);
		then(processor).should().process(eq(eventId), eq("PAYMENT_APPROVED"), eq(APPROVED_AT), captor.capture());
		PaymentApprovedPayload payload = captor.getValue();
		assertThat(payload.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(payload.orderId()).isEqualTo(ORDER_A);
		assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
		assertThat(payload.userId()).isEqualTo(BUYER_ID);
		assertThat(payload.approvedAmount()).isEqualTo(30_000);
		assertThat(payload.amount()).isEqualTo(30_000);
		assertThat(payload.approvedAtValue()).isEqualTo("2026-07-17T10:00:05+09:00");
		assertThat(payload.approvedAt()).isEqualTo(APPROVED_AT);
	}

	@Test
	void handle_invalidUuid_doesNotCallProcessor() throws Exception {
		JsonNode payloadNode = objectMapper.readTree("""
			{
			  "paymentId": "not-a-uuid",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "approvedAt": "2026-07-17T10:00:05+09:00"
			}
			""".formatted(ORDER_A, BUYER_ID));
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			"PAYMENT",
			PAYMENT_ID,
			payloadNode
		);

		assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(OrderException.class);
		then(processor).shouldHaveNoInteractions();
	}
}
