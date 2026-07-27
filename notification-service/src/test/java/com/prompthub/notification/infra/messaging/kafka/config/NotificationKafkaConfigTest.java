package com.prompthub.notification.infra.messaging.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationKafkaConfigTest {

    private final NotificationKafkaConfig kafkaConfig = new NotificationKafkaConfig(
        "localhost:9092", "notification-service", "earliest", false
    );

    @Test
    void createsDedicatedRawStringConsumerWithManualAcknowledgment() {
        ConsumerFactory<String, String> consumerFactory =
            kafkaConfig.notificationKafkaConsumerFactory();
        DefaultErrorHandler errorHandler = kafkaConfig.notificationKafkaErrorHandler(
            kafkaConfig.notificationDltKafkaTemplate(kafkaConfig.notificationDltProducerFactory())
        );

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            kafkaConfig.notificationKafkaListenerContainerFactory(consumerFactory, errorHandler);

        assertThat(consumerFactory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(factory.getContainerProperties().getAckMode())
            .isEqualTo(ContainerProperties.AckMode.MANUAL);
    }
}
