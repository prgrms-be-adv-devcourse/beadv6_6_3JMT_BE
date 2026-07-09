package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.PaymentApprovedEventHandler;
import com.prompthub.order.application.service.event.PaymentRefundedEventHandler;
import com.prompthub.order.application.service.event.PaymentFailedEventHandler;
import com.prompthub.order.application.service.event.PaymentCanceledEventHandler;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentEventConsumerIntegrationTest extends KafkaIntegrationTest {

	private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
	private static final String PAYMENT_EVENTS_DLT_TOPIC = "payment-events.DLT";

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

	@MockitoBean
	private PaymentCanceledEventHandler paymentCanceledEventHandler;

	@BeforeEach
	void clearMocks() {
		clearInvocations(
			paymentApprovedEventHandler,
			paymentRefundedEventHandler,
			paymentFailedEventHandler,
			paymentCanceledEventHandler
		);
	}

	@Test
	@DisplayName("결제 승인 이벤트를 수신하면 PaymentApprovedEventHandler가 호출된다")
	void consumePaymentApprovedEvent() {
		// given
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();

		Map<String, Object> payload = new HashMap<>();
		payload.put("paymentId", paymentId.toString());
		payload.put("orderId", orderId.toString());
		payload.put("userId", buyerId.toString());
		payload.put("amount", 30000);
		payload.put("approvedAt", OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());

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
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> 
			verify(paymentApprovedEventHandler).handle(any(EventMessage.class))
		);
	}

	@Test
	@DisplayName("결제 환불 이벤트를 수신하면 PaymentRefundedEventHandler가 호출된다")
	void consumePaymentRefundedEvent() {
		// given
		UUID paymentId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();

		Map<String, Object> payload = new HashMap<>();
		payload.put("paymentId", paymentId.toString());
		payload.put("orderId", orderId.toString());
		payload.put("userId", buyerId.toString());
		payload.put("amount", 30000);
		payload.put("refundedAt", OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());

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
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(paymentRefundedEventHandler).handle(any(EventMessage.class))
		);
	}

	@Test
	@DisplayName("PaymentEventConsumer는 payment-events만 구독하고 기존 payment.approved/payment.refunded는 구독하지 않는다")
	void kafkaListener_shouldSubscribeOnlyPaymentEventsTopic() {
		assertThat(kafkaListenerEndpointRegistry.getListenerContainers())
			.flatExtracting(container -> Arrays.asList(container.getContainerProperties().getTopics()))
			.contains(PAYMENT_EVENTS_TOPIC)
			.doesNotContain("payment.approved", "payment.refunded");
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
	@DisplayName("PAYMENT_FAILED와 PAYMENT_CANCELED는 정상 소비하고 DLT로 보내지 않는다")
	void consumePaymentEvents_whenNonBusinessEventTypes_thenAckWithoutDlt() throws Exception {
		try (Consumer<String, String> dltConsumer = stringConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, PAYMENT_EVENTS_DLT_TOPIC);
			KafkaTemplate<String, String> rawTemplate = rawStringKafkaTemplate();

			rawTemplate.send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), ignoredEvent("PAYMENT_FAILED"))
				.get(5, TimeUnit.SECONDS);
			rawTemplate.send(PAYMENT_EVENTS_TOPIC, UUID.randomUUID().toString(), ignoredEvent("PAYMENT_CANCELED"))
				.get(5, TimeUnit.SECONDS);

			await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
				verify(paymentFailedEventHandler).handle(any(EventMessage.class));
				verify(paymentCanceledEventHandler).handle(any(EventMessage.class));
				verify(paymentApprovedEventHandler, never()).handle(any(EventMessage.class));
				verify(paymentRefundedEventHandler, never()).handle(any(EventMessage.class));
			});
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
}
