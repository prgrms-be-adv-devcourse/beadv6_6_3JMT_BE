package com.prompthub.notification.infra.messaging.kafka.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration
public class KafkaConfig {
    private final String bootstrapServers;
    private final String consumerGroup;
    private final String autoOffsetReset;
    private final boolean enableAutoCommit;
    private final long retryIntervalMs;
    private final long maxRetryAttempts;
    private final boolean listenerAutoStartup;

    public KafkaConfig(
        @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
        @Value("${notification.kafka.consumer-group:notification-service}") String consumerGroup,
        @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
        @Value("${spring.kafka.consumer.enable-auto-commit:false}") boolean enableAutoCommit,
        @Value("${notification.kafka.retry-interval-ms:1000}") long retryIntervalMs,
        @Value("${notification.kafka.max-retry-attempts:3}") long maxRetryAttempts,
        @Value("${spring.kafka.listener.auto-startup:true}") boolean listenerAutoStartup
    ) {
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup = consumerGroup;
        this.autoOffsetReset = autoOffsetReset;
        this.enableAutoCommit = enableAutoCommit;
        this.retryIntervalMs = retryIntervalMs;
        this.maxRetryAttempts = maxRetryAttempts;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    @Bean
    public ProducerFactory<String, String> orderEventProducerFactory() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> orderEventKafkaTemplate(ProducerFactory<String, String> orderEventProducerFactory) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> orderEventConsumerFactory() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> orderEventKafkaListenerContainerFactory(
        ConsumerFactory<String, String> orderEventConsumerFactory,
        DefaultErrorHandler orderEventKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderEventConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(orderEventKafkaErrorHandler);
        factory.setAutoStartup(listenerAutoStartup);
        return factory;
    }

    @Bean
    public DefaultErrorHandler orderEventKafkaErrorHandler(KafkaTemplate<String, String> orderEventKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            orderEventKafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
        );
        return new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, maxRetryAttempts));
    }
}
