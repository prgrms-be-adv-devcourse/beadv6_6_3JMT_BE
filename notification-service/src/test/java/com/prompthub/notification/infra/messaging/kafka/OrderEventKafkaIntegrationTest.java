package com.prompthub.notification.infra.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.listener.auto-startup=true",
    "notification.kafka.retry-interval-ms=0",
    "notification.kafka.max-retry-attempts=0"
})
@EmbeddedKafka(partitions = 1, topics = {"order-events", "order-events.DLT"})
@ActiveProfiles("test")
class OrderEventKafkaIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Test
    void malformedOrderEventIsPublishedToOrderEventsDlt() throws Exception {
        try (Consumer<String, String> dltConsumer = dltConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, "order-events.DLT");

            rawStringKafkaTemplate().send("order-events", UUID.randomUUID().toString(), "{").get(5, TimeUnit.SECONDS);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                dltConsumer, "order-events.DLT", Duration.ofSeconds(10)
            );
            assertThat(record.value()).isEqualTo("{");
        }
    }

    private KafkaTemplate<String, String> rawStringKafkaTemplate() {
        Map<String, Object> properties = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
    }
}
