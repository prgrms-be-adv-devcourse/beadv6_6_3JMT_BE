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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayIntegrationTest extends KafkaIntegrationTest {

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
    @DisplayName("Outbox Event가 DB에 저장되어 있으면 OutboxRelay가 Kafka 토픽으로 발행한다")
    void outboxRelayPublishesEventsToKafka() {
        // given
        UUID orderId = UUID.randomUUID();
        String payload = """
            {"eventType":"ORDER_PAID","payload":{"orderId":"%s"}}
            """.formatted(orderId);
        OutboxEvent event = OutboxEvent.orderPaid(orderId, payload, LocalDateTime.now());
        outboxEventRepository.save(event);

        // when
        outboxRelay.publishPendingEvents();

        // then
        // 1. DB 상태 검증
        OutboxEvent updatedEvent = outboxEventPersistence.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        // 2. Kafka 메시지 검증
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(10000));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        boolean messageFound = false;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(orderId.toString()) && record.value().contains("ORDER_PAID")) {
                messageFound = true;
                break;
            }
        }
        assertThat(messageFound).isTrue();
    }
}
