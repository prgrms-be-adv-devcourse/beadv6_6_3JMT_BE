package com.prompthub.notification.infra.messaging.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.timeout;

@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "prompthub.notification.kafka.listener-auto-startup=true"
})
@EmbeddedKafka(partitions = 1, topics = {"order-events-test", "order-events-test.DLT"})
@ActiveProfiles("test")
class NotificationEventConsumerEmbeddedKafkaTest {

    private static final String ORDER_PAID_RELAY_VALUE = """
        {
          "eventId":"00000000-0000-0000-0000-000000000101",
          "eventType":"ORDER_PAID",
          "occurredAt":"2026-07-27T10:00:00",
          "aggregateType":"ORDER",
          "aggregateId":"00000000-0000-0000-0000-000000000201",
          "payload":{
            "orderId":"00000000-0000-0000-0000-000000000201",
            "buyerId":"00000000-0000-0000-0000-000000000301",
            "orderNumber":"ORD-20260727-0001"
          }
        }
        """;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private OrderEventHandler orderEventHandler;

    @Test
    void consumesHeaderlessRelayJsonAsRawString() throws Exception {
        kafkaTemplate.send("order-events-test", "order-201", ORDER_PAID_RELAY_VALUE).get();

        then(orderEventHandler).should(timeout(10_000)).handle(any());
    }

    @Test
    void sendsMalformedRawJsonToSamePartitionDltWithoutInvokingHandler() {
        assertDltValue("{");
        then(orderEventHandler).shouldHaveNoInteractions();
    }

    @Test
    void sendsMissingEnvelopeMetadataToDltWithoutInvokingHandler() {
        assertDltValue("""
            {
              "eventType":"ORDER_PAID",
              "occurredAt":"2026-07-27T10:00:00",
              "aggregateType":"ORDER",
              "aggregateId":"00000000-0000-0000-0000-000000000201",
              "payload":{}
            }
            """);
        then(orderEventHandler).shouldHaveNoInteractions();
    }

    @Test
    void retriesRuntimeHandlingFailureThreeTimesThenSendsOriginalValueToDlt() {
        willThrow(new IllegalStateException("database temporarily unavailable"))
            .given(orderEventHandler)
            .handle(any());

        assertDltValue(ORDER_PAID_RELAY_VALUE);

        then(orderEventHandler).should(timeout(10_000).times(4)).handle(any());
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> properties = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString(),
            ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + System.nanoTime(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
            properties,
            new StringDeserializer(),
            new StringDeserializer()
        ).createConsumer();
        consumer.subscribe(Set.of("order-events-test.DLT"));
        KafkaTestUtils.getRecords(consumer, Duration.ofMillis(100));
        consumer.seekToEnd(consumer.assignment());
        return consumer;
    }

    private void assertDltValue(String rawValue) {
        try (Consumer<String, String> consumer = dltConsumer()) {
            kafkaTemplate.send("order-events-test", "order-invalid", rawValue).get();
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(
                consumer,
                Duration.ofSeconds(10)
            );

            assertThat(records.records("order-events-test.DLT"))
                .extracting(ConsumerRecord::value)
                .contains(rawValue);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
