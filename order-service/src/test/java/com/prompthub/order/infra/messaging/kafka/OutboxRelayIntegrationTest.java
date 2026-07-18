package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
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
import org.springframework.test.util.ReflectionTestUtils;
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
    private OutboxEventPersistence outboxEventPersistence;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OrderEventMessageFactory orderEventMessageFactory;

    @Autowired
    private OutboxEventAppender outboxEventAppender;

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
    @DisplayName("Outbox Event가 DB에 저장되어 있으면 product-service가 수신 가능한 ORDER_PAID 이벤트를 Kafka로 발행한다")
    void outboxRelayPublishesProductServiceCompatibleOrderPaidEventToKafka() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID orderProductIdA1 = UUID.randomUUID();
        UUID orderProductIdB1 = UUID.randomUUID();
        UUID orderProductIdA2 = UUID.randomUUID();
        UUID orderProductIdC1 = UUID.randomUUID();
        UUID productIdA1 = UUID.randomUUID();
        UUID productIdB1 = UUID.randomUUID();
        UUID productIdA2 = UUID.randomUUID();
        UUID productIdC1 = UUID.randomUUID();
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        UUID sellerC = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        Order order = paidOrder(orderId, buyerId, 39_600, occurredAt,
            new ProductLine(orderProductIdA1, productIdA1, sellerA, "A1", 9_900),
            new ProductLine(orderProductIdB1, productIdB1, sellerB, "B1", 9_900),
            new ProductLine(orderProductIdA2, productIdA2, sellerA, "A2", 9_900),
            new ProductLine(orderProductIdC1, productIdC1, sellerC, "C1", 9_900)
        );
        OrderPaidPayload payload = OrderPaidPayload.from(order);
        var message = orderEventMessageFactory.createOrderPaidMessage(order.getId(), payload);
        outboxEventAppender.append(message);

        outboxRelay.publishPendingEvents();

        OutboxEvent updatedEvent = outboxEventPersistence.findById(message.eventId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> matchedRecord = null;
        JsonNode matchedMessage = null;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(orderId.toString()) && record.value().contains("ORDER_PAID")) {
                matchedRecord = record;
                matchedMessage = objectMapper.readTree(record.value());
                break;
            }
        }
        assertThat(matchedRecord).isNotNull();
        assertThat(matchedRecord.key()).isEqualTo(orderId.toString());
        assertThat(matchedMessage).isNotNull();
        assertThat(matchedMessage.path("eventId").asText()).isEqualTo(message.eventId().toString());
        assertThat(matchedMessage.path("eventType").asText()).isEqualTo("ORDER_PAID");
        assertThat(matchedMessage.path("aggregateType").asText()).isEqualTo("ORDER");
        assertThat(matchedMessage.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("totalOrderAmount").intValue()).isEqualTo(39_600);
        JsonNode products = matchedMessage.path("payload").path("products");
        assertThat(products).hasSize(4);
        assertThat(products).allSatisfy(product -> assertThat(product.has("productId")).isTrue());
        assertThat(products).extracting(product -> product.path("productId").asText())
            .containsExactly(productIdA1.toString(), productIdB1.toString(), productIdA2.toString(), productIdC1.toString());
        assertThat(products).extracting(product -> product.path("sellerId").asText())
            .containsExactly(sellerA.toString(), sellerB.toString(), sellerA.toString(), sellerC.toString());
    }

    @Test
    @DisplayName("Outbox Event가 DB에 저장되어 있으면 product-service가 수신 가능한 ORDER_REFUND 이벤트를 Kafka로 발행한다")
    void outboxRelayPublishesProductServiceCompatibleOrderRefundEventToKafka() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        Order order = paidOrder(orderId, buyerId, 30_000, occurredAt,
            new ProductLine(orderProductId, productId, sellerId, "refund-target", 9_900),
            new ProductLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sibling", 20_100)
        );
        OrderProduct refundedProduct = order.getOrderProducts().getFirst();
        order.refundOrderProduct(refundedProduct.getId(), refundedProduct.getProductAmount(), occurredAt);
        OrderRefundPayload payload = OrderRefundPayload.from(order, refundedProduct, occurredAt);
        var message = orderEventMessageFactory.createOrderRefundMessage(order.getId(), payload);
        outboxEventAppender.append(message);

        outboxRelay.publishPendingEvents();

        OutboxEvent updatedEvent = outboxEventPersistence.findById(message.eventId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> matchedRecord = null;
        JsonNode matchedMessage = null;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(orderId.toString()) && record.value().contains("ORDER_REFUND")) {
                matchedRecord = record;
                matchedMessage = objectMapper.readTree(record.value());
                break;
            }
        }
        assertThat(matchedRecord).isNotNull();
        assertThat(matchedRecord.key()).isEqualTo(orderId.toString());
        assertThat(matchedMessage).isNotNull();
        assertThat(matchedMessage.path("eventId").asText()).isEqualTo(message.eventId().toString());
        assertThat(matchedMessage.path("eventType").asText()).isEqualTo("ORDER_REFUND");
        assertThat(matchedMessage.path("aggregateType").asText()).isEqualTo("ORDER");
        assertThat(matchedMessage.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(matchedMessage.path("payload").has("paymentId")).isFalse();
        assertThat(matchedMessage.path("payload").has("totalRefundAmount")).isFalse();
        assertThat(matchedMessage.path("payload").path("totalOrderAmount").intValue()).isEqualTo(30_000);
        assertThat(matchedMessage.path("payload").path("products")).hasSize(1);
        assertThat(matchedMessage.path("payload").path("products").get(0).has("productId")).isTrue();
        assertThat(matchedMessage.path("payload").path("products").get(0).path("productId").asText())
            .isEqualTo(productId.toString());
        assertThat(matchedMessage.path("payload").path("products").get(0).path("sellerId").asText())
            .isEqualTo(sellerId.toString());
        assertThat(matchedMessage.path("payload").path("products").get(0).has("refundAmount")).isFalse();
        assertThat(matchedMessage.path("payload").path("products").get(0).path("productAmount").intValue())
            .isEqualTo(9900);
    }

    private Order paidOrder(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        LocalDateTime paidAt,
        ProductLine... lines
    ) {
        Order order = Order.create(buyerId, "ORD-" + orderId, totalOrderAmount);
        ReflectionTestUtils.setField(order, "id", orderId);
        for (ProductLine line : lines) {
            OrderProduct product = OrderProduct.create(
                line.productId(), line.sellerId(), line.productTitle(), "PROMPT", "GPT-4", line.productAmount()
            );
            ReflectionTestUtils.setField(product, "id", line.orderProductId());
            order.addOrderProduct(product);
        }
        order.markPaid(paidAt);
        return order;
    }

    private record ProductLine(
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        String productTitle,
        int productAmount
    ) {
    }
}
