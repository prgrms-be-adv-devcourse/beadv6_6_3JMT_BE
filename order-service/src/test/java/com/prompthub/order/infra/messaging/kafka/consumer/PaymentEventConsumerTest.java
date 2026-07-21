package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventConsumer;
import com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

	@Mock
	private PaymentEventRouter paymentEventRouter;

	@Mock
	private org.springframework.kafka.support.Acknowledgment acknowledgment;

	private ObjectMapper objectMapper = new ObjectMapper();

	private PaymentEventConsumer consumer;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
		consumer = new PaymentEventConsumer(paymentEventRouter, objectMapper);
	}

	@Test
	@DisplayName("Kafka 수신 메시지를 라우터로 위임한다")
	void consume_delegatesToRouter() throws Exception {
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			LocalDateTime.now(),
			"PAYMENT",
			UUID.randomUUID(),
			objectMapper.createObjectNode()
		);

		String jsonMessage = objectMapper.writeValueAsString(message);

		consumer.consume(jsonMessage, acknowledgment);

		then(paymentEventRouter).should().route(any());
		then(acknowledgment).should().acknowledge();
	}

	@ParameterizedTest
	@ValueSource(strings = {"PAYMENT_CANCELED"})
	@DisplayName("미지원 결제 이벤트는 라우터 호출 없이 ACK한다")
	void consume_unsupportedEvent_acknowledgesWithoutRouting(String eventType) throws Exception {
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			eventType,
			LocalDateTime.now(),
			"PAYMENT",
			UUID.randomUUID(),
			objectMapper.createObjectNode()
		);

		consumer.consume(objectMapper.writeValueAsString(message), acknowledgment);

		then(paymentEventRouter).shouldHaveNoInteractions();
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("라우터 처리에 실패하면 예외를 전파하고 ACK하지 않는다")
	void consume_whenRouterFails_thenPropagatesExceptionWithoutAck() throws Exception {
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			LocalDateTime.now(),
			"PAYMENT",
			UUID.randomUUID(),
			objectMapper.createObjectNode()
		);
		OrderException expected = new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		willThrow(expected).given(paymentEventRouter).route(any());

		String jsonMessage = objectMapper.writeValueAsString(message);

		assertThatThrownBy(() -> consumer.consume(jsonMessage, acknowledgment))
			.isSameAs(expected);
		verifyNoInteractions(acknowledgment);
	}
}
