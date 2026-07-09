package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventConsumer;
import com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

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

		then(paymentEventRouter).should().route(org.mockito.ArgumentMatchers.any());
		then(acknowledgment).should().acknowledge();
	}
}
