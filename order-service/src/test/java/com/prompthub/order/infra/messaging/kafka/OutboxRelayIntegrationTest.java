package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.infra.messaging.kafka.producer.OutboxRelay;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayIntegrationTest extends KafkaIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventPersistence outboxEventPersistence;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(
            consumerProps, new StringDeserializer(), new StringDeserializer());
        consumer = cf.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "order-events");
        outboxEventPersistence.deleteAll(); // clear DB before each test
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    @DisplayName("ORDER_CREATED Outbox Event는 주문 ID를 Kafka key로 발행한다")
    void outboxRelayPublishesOrderCreatedWithOrderIdKafkaKey() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        String payload = """
            {
              "eventId": "%s",
              "eventType": "ORDER_CREATED",
              "occurredAt": "%s",
              "aggregateType": "ORDER",
              "aggregateId": "%s",
              "payload": {
                "orderId": "%s",
                "buyerId": "%s",
                "totalAmount": 9900,
                "createdAt": "%s"
              }
            }
            """.formatted(eventId, occurredAt, orderId, orderId, buyerId, occurredAt);
        OutboxEvent event = OutboxEvent.orderCreated(eventId, orderId, payload, occurredAt);
        outboxEventRepository.save(event);

        outboxRelay.publishPendingEvents();

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        ConsumerRecord<String, String> orderCreatedRecord = null;
        for (ConsumerRecord<String, String> record : records.records("order-events")) {
            if (record.value().contains("ORDER_CREATED")) {
                orderCreatedRecord = record;
                break;
            }
        }

        assertThat(orderCreatedRecord).isNotNull();
        assertThat(orderCreatedRecord.key()).isEqualTo(orderId.toString());
        JsonNode message = objectMapper.readTree(orderCreatedRecord.value());
        assertThat(message.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(message.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
    }

	@Test
    @DisplayName("Outbox Event가 DB에 저장되어 있으면 product-service가 수신 가능한 ORDER_PAID 이벤트를 Kafka로 발행한다")
    void outboxRelayPublishesProductServiceCompatibleOrderPaidEventToKafka() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        String payload = """
            {
              "eventId": "%s",
              "eventType": "ORDER_PAID",
              "version": 1,
              "occurredAt": "%s",
              "aggregateId": "%s",
              "payload": {
                "orderId": "%s",
                "buyerId": "%s",
                "totalOrderAmount": 9900,
                "totalProductCount": 1,
                "paidAt": "%s",
                "products": [
                  {
                    "orderProductId": "%s",
                    "productId": "%s",
                    "sellerId": "%s",
                    "productTitle": "test",
                    "productType": "PROMPT",
                    "productAmount": 9900
                  }
                ]
              }
            }
            """.formatted(eventId, occurredAt, orderId, orderId, buyerId, occurredAt,
            orderProductId, productId, sellerId);
        OutboxEvent event = OutboxEvent.orderPaid(eventId, orderId, payload, occurredAt);
        outboxEventRepository.save(event);

        // when
        outboxRelay.publishPendingEvents();

        // then
        // 1. DB 상태 검증
        OutboxEvent updatedEvent = outboxEventPersistence.findById(event.getEventId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        // 2. Kafka 메시지 검증
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        JsonNode matchedMessage = null;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(orderId.toString()) && record.value().contains("ORDER_PAID")) {
                matchedMessage = objectMapper.readTree(record.value());
                break;
            }
        }
        assertThat(matchedMessage).isNotNull();
        assertThat(matchedMessage.path("eventType").asText()).isEqualTo("ORDER_PAID");
        assertThat(matchedMessage.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("products")).hasSize(1);
        assertThat(matchedMessage.path("payload").path("products").get(0).path("productId").asText())
            .isEqualTo(productId.toString());
    }

    @Test
    @DisplayName("Outbox Event가 DB에 저장되어 있으면 product-service가 수신 가능한 ORDER_REFUND 이벤트를 Kafka로 발행한다")
    void outboxRelayPublishesProductServiceCompatibleOrderRefundEventToKafka() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        String payload = """
            {
              "eventId": "%s",
              "eventType": "ORDER_REFUND",
              "version": 1,
              "occurredAt": "%s",
              "aggregateId": "%s",
              "payload": {
                "orderId": "%s",
                "paymentId": "%s",
                "buyerId": "%s",
                "totalRefundAmount": 9900,
                "totalProductCount": 1,
                "refundedAt": "%s",
                "products": [
                  {
                    "orderProductId": "%s",
                    "productId": "%s",
                    "sellerId": "%s",
                    "productTitle": "test",
                    "productType": "PROMPT",
                    "refundAmount": 9900
                  }
                ]
              }
            }
            """.formatted(eventId, occurredAt, orderId, orderId, paymentId, buyerId, occurredAt,
            orderProductId, productId, sellerId);
        OutboxEvent event = OutboxEvent.orderRefund(eventId, orderId, payload, occurredAt);
        outboxEventRepository.save(event);

        outboxRelay.publishPendingEvents();

        OutboxEvent updatedEvent = outboxEventPersistence.findById(event.getEventId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        JsonNode matchedMessage = null;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(orderId.toString()) && record.value().contains("ORDER_REFUND")) {
                matchedMessage = objectMapper.readTree(record.value());
                break;
            }
        }
        assertThat(matchedMessage).isNotNull();
        assertThat(matchedMessage.path("eventType").asText()).isEqualTo("ORDER_REFUND");
        assertThat(matchedMessage.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(matchedMessage.path("payload").path("totalRefundAmount").intValue()).isEqualTo(9900);
        assertThat(matchedMessage.path("payload").path("products")).hasSize(1);
        assertThat(matchedMessage.path("payload").path("products").get(0).path("refundAmount").intValue())
            .isEqualTo(9900);
    }
}
