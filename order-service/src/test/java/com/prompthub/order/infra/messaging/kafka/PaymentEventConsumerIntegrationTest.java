package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.PaymentApprovedEventHandler;
import com.prompthub.order.application.service.event.PaymentFailedEventHandler;
import com.prompthub.order.application.service.event.PaymentRefundedEventHandler;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PaymentEventConsumerIntegrationTest extends KafkaIntegrationTest {

	private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
	private static final String PAYMENT_EVENTS_DLT_TOPIC = "payment-events.DLT";
	private static final String ORDER_SERVICE_CONSUMER_GROUP = "order-service";
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafkaBroker;

	@Autowired
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	@MockitoBean
	private PaymentApprovedEventHandler paymentApprovedEventHandler;

	@MockitoBean
	private PaymentRefundedEventHandler paymentRefundedEventHandler;

	@MockitoBean
	private PaymentFailedEventHandler paymentFailedEventHandler;

	@BeforeEach
	void resetMocks() {
		reset(
			paymentApprovedEventHandler,
			paymentRefundedEventHandler,
			paymentFailedEventHandler
		);
	}

	@Test
	@DisplayName("결제 승인 이벤트를 수신하면 PaymentApprovedEventHandler가 호출된다")
	void consumePaymentApprovedEvent() {
		// given
		UUID orderId = UUID.randomUUID();

		Map<String, Object> payload = new HashMap<>();
		payload.put("orderId", orderId.toString());
		payload.put("approvedAmount", 30000);
		payload.put("approvedAt", "2026-07-17T10:00:05+09:00");

		Map<String, Object> message = new HashMap<>();
		message.put("eventId", UUID.randomUUID().toString());
		message.put("eventType", "PAYMENT_APPROVED");
		message.put("occurredAt", LocalDateTime.now().toString());
		message.put("aggregateType", "ORDER");
		message.put("aggregateId", orderId.toString());
		message.put("payload", payload);

		// when
		kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, orderId.toString(), message);

		// then
		ArgumentCaptor<EventMessage<JsonNode>> captor = eventMessageCaptor();
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(paymentApprovedEventHandler).handle(captor.capture())
		);
		EventMessage<JsonNode> capturedMessage = captor.getValue();
		assertThat(capturedMessage.aggregateType()).isEqualTo("ORDER");
		assertThat(capturedMessage.aggregateId()).isEqualTo(orderId);
		assertThat(capturedMessage.payload().path("orderId").asText()).isEqualTo(orderId.toString());
		assertThat(capturedMessage.payload().path("approvedAmount").asInt()).isEqualTo(30_000);
		assertThat(capturedMessage.payload().path("approvedAt").asText())
			.isEqualTo("2026-07-17T10:00:05+09:00");
	}

	@Test
	@DisplayName("결제 환불 이벤트를 수신하면 PaymentRefundedEventHandler가 호출된다")
	void consumePaymentRefundedEvent() {
		// given
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID orderProductId = UUID.randomUUID();

		Map<String, Object> payload = new HashMap<>();
		payload.put("paymentId", paymentId.toString());
		payload.put("orderId", orderId.toString());
		payload.put("userId", userId.toString());
		payload.put("orderProductId", orderProductId.toString());
		payload.put("amount", 10000);
		payload.put("paymentStatus", "PARTIAL_REFUNDED");
		payload.put("refundedAt", "2026-07-17T11:00:00+09:00");

		Map<String, Object> message = new HashMap<>();
		message.put("eventId", UUID.randomUUID().toString());
		message.put("eventType", "PAYMENT_REFUNDED");
		message.put("occurredAt", LocalDateTime.now().toString());
		message.put("aggregateType", "ORDER");
		message.put("aggregateId", orderId.toString());
		message.put("payload", payload);

		// when
		kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, orderId.toString(), message);

		// then
		ArgumentCaptor<EventMessage<JsonNode>> captor = eventMessageCaptor();
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(paymentRefundedEventHandler).handle(captor.capture())
		);
		EventMessage<JsonNode> capturedMessage = captor.getValue();
		assertThat(capturedMessage.aggregateType()).isEqualTo("ORDER");
		assertThat(capturedMessage.aggregateId()).isEqualTo(orderId);
		JsonNode capturedPayload = capturedMessage.payload();
		assertThat(capturedPayload.path("paymentId").asText()).isEqualTo(paymentId.toString());
		assertThat(capturedPayload.path("orderId").asText()).isEqualTo(orderId.toString());
		assertThat(capturedPayload.path("userId").asText()).isEqualTo(userId.toString());
		assertThat(capturedPayload.path("orderProductId").asText()).isEqualTo(orderProductId.toString());
		assertThat(capturedPayload.path("amount").asInt()).isEqualTo(10_000);
		assertThat(capturedPayload.path("paymentStatus").asText()).isEqualTo("PARTIAL_REFUNDED");
		assertThat(capturedPayload.path("refundedAt").asText())
			.isEqualTo("2026-07-17T11:00:00+09:00");
	}

	@Test
	@DisplayName("PaymentEventConsumer는 order-service 그룹으로 payment-events만 구독하고 기존 payment.approved/payment.refunded는 구독하지 않는다")
	void kafkaListener_shouldUseOrderServiceGroupAndSubscribeOnlyPaymentEventsTopic() {
		assertThat(kafkaListenerEndpointRegistry.getListenerContainers())
			.flatExtracting(container -> Arrays.asList(container.getContainerProperties().getTopics()))
			.contains(PAYMENT_EVENTS_TOPIC)
			.doesNotContain("payment.approved", "payment.refunded");
		assertThat(kafkaListenerEndpointRegistry.getListenerContainers())
			.filteredOn(container -> Arrays.asList(container.getContainerProperties().getTopics())
				.contains(PAYMENT_EVENTS_TOPIC))
			.extracting(container -> container.getContainerProperties().getGroupId())
			.containsExactly(ORDER_SERVICE_CONSUMER_GROUP);
	}

	@Test
	@DisplayName("알 수 없는 eventType은 정상 소비하고 DLT로 보내지 않는다")
	void consumePaymentEvents_whenUnknownEventType_thenAckWithoutDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);

			rawStringKafkaTemplate().send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), """
				{
				  "eventId": "%s",
				  "eventType": "UNKNOWN_EVENT",
				  "occurredAt": "2026-06-19T12:00:00",
				  "aggregateType": "ORDER",
				  "aggregateId": "%s",
				  "payload": {}
				}
				""".formatted(UUID.randomUUID(), UUID.randomUUID())).get(5, TimeUnit.SECONDS);

			await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
				verify(paymentApprovedEventHandler, never()).handle(any(EventMessage.class));
				verify(paymentRefundedEventHandler, never()).handle(any(EventMessage.class));
			});
			assertThat(dltConsumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("PAYMENT_FAILED는 정상 소비하고 DLT로 보내지 않는다")
	void consumePaymentFailed_thenAckWithoutDlt() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();

		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			rawStringKafkaTemplate()
				.send(
					PAYMENT_EVENTS_TOPIC,
					paymentId.toString(),
					paymentFailedEvent(eventId, paymentId, orderId, userId)
				)
				.get(5, TimeUnit.SECONDS);

			ArgumentCaptor<EventMessage<JsonNode>> captor = eventMessageCaptor();
			await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
				verify(paymentFailedEventHandler).handle(captor.capture());
				verify(paymentApprovedEventHandler, never()).handle(any(EventMessage.class));
				verify(paymentRefundedEventHandler, never()).handle(any(EventMessage.class));
			});
			EventMessage<JsonNode> capturedMessage = captor.getValue();
			assertThat(capturedMessage.eventId()).isEqualTo(eventId);
			assertThat(capturedMessage.eventType()).isEqualTo("PAYMENT_FAILED");
			assertThat(capturedMessage.aggregateId()).isEqualTo(paymentId);
			assertThat(capturedMessage.payload().path("paymentId").asText())
				.isEqualTo(paymentId.toString());
			assertThat(capturedMessage.payload().path("orderId").asText())
				.isEqualTo(orderId.toString());
			assertThat(capturedMessage.payload().path("userId").asText())
				.isEqualTo(userId.toString());
			assertThat(dltConsumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("결제 실패 핸들러 예외는 3회 재시도 후 원본 key와 payload로 DLT에 이동한다")
	void consumePaymentFailed_whenHandlerFails_thenRetryAndSendToDlt() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		String rawPaymentFailedEvent = paymentFailedEvent(eventId, paymentId, orderId, userId);
		willThrow(new OrderException(ErrorCode.INVALID_INPUT_VALUE))
			.given(paymentFailedEventHandler)
			.handle(any(EventMessage.class));

		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			rawStringKafkaTemplate()
				.send(
					PAYMENT_EVENTS_TOPIC,
					paymentId.toString(),
					rawPaymentFailedEvent
				)
				.get(5, TimeUnit.SECONDS);

			ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
				dltConsumer,
				PAYMENT_EVENTS_DLT_TOPIC,
				Duration.ofSeconds(15)
			);

			verify(paymentFailedEventHandler, times(4)).handle(any(EventMessage.class));
			assertThat(dltRecord.partition()).isZero();
			assertThat(dltRecord.key()).isEqualTo(paymentId.toString());
			assertThat(objectMapper.readValue(dltRecord.value(), String.class))
				.isEqualTo(rawPaymentFailedEvent);
			assertThat(dltRecord.value()).contains(
				"PAYMENT_FAILED",
				eventId.toString(),
				orderId.toString()
			);
		}
	}

	@Test
	@DisplayName("핸들러 예외는 3회 재시도 후 payment-events.DLT로 이동한다")
	void consumePaymentApproved_whenHandlerFails_thenRetryAndSendToDlt() throws Exception {
		UUID orderId = UUID.randomUUID();
		willThrow(new OrderException(ErrorCode.INVALID_INPUT_VALUE))
			.given(paymentApprovedEventHandler)
			.handle(any(EventMessage.class));

		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			rawStringKafkaTemplate()
				.send(
					PAYMENT_EVENTS_TOPIC,
					orderId.toString(),
					paymentApprovedEvent(orderId)
				)
				.get(5, TimeUnit.SECONDS);

			ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
				dltConsumer,
				PAYMENT_EVENTS_DLT_TOPIC,
				Duration.ofSeconds(15)
			);

			verify(paymentApprovedEventHandler, times(4)).handle(any(EventMessage.class));
			assertThat(dltRecord.key()).isEqualTo(orderId.toString());
			assertThat(dltRecord.value()).contains("PAYMENT_APPROVED", orderId.toString());
		}
	}

	@Test
	@DisplayName("PAYMENT_CANCELED는 미지원 이벤트로 ACK하고 DLT로 보내지 않는다")
	void consumePaymentCanceled_thenAckAsUnsupportedWithoutDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			rawStringKafkaTemplate()
				.send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), ignoredEvent("PAYMENT_CANCELED"))
				.get(5, TimeUnit.SECONDS);

			assertThat(dltConsumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
			verify(paymentApprovedEventHandler, never()).handle(any(EventMessage.class));
			verify(paymentRefundedEventHandler, never()).handle(any(EventMessage.class));
			verify(paymentFailedEventHandler, never()).handle(any(EventMessage.class));
		}
	}

	@Test
	@DisplayName("PAYMENT_REFUND_FAILED는 실패 핸들러로 라우팅하고 DLT로 보내지 않는다")
	void consumePaymentRefundFailed_thenRoutesWithoutDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			rawStringKafkaTemplate()
				.send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), ignoredEvent("PAYMENT_REFUND_FAILED"))
				.get(5, TimeUnit.SECONDS);

			await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
				verify(paymentRefundedEventHandler).handleFailed(any(EventMessage.class))
			);
			assertThat(dltConsumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("잘못된 JSON 메시지는 payment-events.DLT로 이동한다")
	void consumePaymentEvents_whenInvalidJson_thenSendToPaymentEventsDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);

			rawStringKafkaTemplate().send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), "{")
				.get(5, TimeUnit.SECONDS);

			ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
				dltConsumer,
				PAYMENT_EVENTS_DLT_TOPIC,
				Duration.ofSeconds(10)
			);
			assertThat(dltRecord.value()).contains("{");
		}
	}

	@Test
	@DisplayName("처리 대상 이벤트의 payload 누락은 payment-events.DLT로 이동한다")
	void consumePaymentEvents_whenMissingPayload_thenSendToPaymentEventsDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);

			rawStringKafkaTemplate().send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), """
				{
				  "eventId": "%s",
				  "eventType": "PAYMENT_APPROVED",
				  "occurredAt": "2026-06-19T12:00:00",
				  "aggregateType": "ORDER",
				  "aggregateId": "%s"
				}
				""".formatted(UUID.randomUUID(), UUID.randomUUID())).get(5, TimeUnit.SECONDS);

			ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
				dltConsumer,
				PAYMENT_EVENTS_DLT_TOPIC,
				Duration.ofSeconds(10)
			);
			assertThat(dltRecord.value()).contains("PAYMENT_APPROVED");
		}
	}

	private String ignoredEvent(String eventType) {
		return """
			{
			  "eventId": "%s",
			  "eventType": "%s",
			  "occurredAt": "2026-06-19T12:00:00",
			  "aggregateType": "ORDER",
			  "aggregateId": "%s",
			  "payload": {}
			}
			""".formatted(UUID.randomUUID(), eventType, UUID.randomUUID());
	}

	private String paymentApprovedEvent(UUID orderId) {
		return """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_APPROVED",
			  "occurredAt": "2026-06-19T12:00:00",
			  "aggregateType": "ORDER",
			  "aggregateId": "%s",
			  "payload": {
			    "orderId": "%s",
			    "approvedAmount": 30000,
			    "approvedAt": "2026-07-17T10:00:05+09:00"
			  }
			}
			""".formatted(
			UUID.randomUUID(),
			orderId,
			orderId
		);
	}

	private String paymentFailedEvent(UUID eventId, UUID paymentId, UUID orderId, UUID userId) {
		return """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_FAILED",
			  "occurredAt": "2026-06-19T12:00:00",
			  "aggregateType": "PAYMENT",
			  "aggregateId": "%s",
			  "payload": {
			    "paymentId": "%s",
			    "orderId": "%s",
			    "userId": "%s"
			  }
			}
			""".formatted(
			eventId,
			paymentId,
			paymentId,
			orderId,
			userId
		);
	}

	private KafkaTemplate<String, String> rawStringKafkaTemplate() {
		Map<String, Object> properties = KafkaTestUtils.producerProps(embeddedKafkaBroker);
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
	}

	private Consumer<String, String> stringConsumer() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(
			properties,
			new StringDeserializer(),
			new StringDeserializer()
		).createConsumer();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private ArgumentCaptor<EventMessage<JsonNode>> eventMessageCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(EventMessage.class);
	}
}
