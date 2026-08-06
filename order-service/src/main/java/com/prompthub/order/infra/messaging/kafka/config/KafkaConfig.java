package com.prompthub.order.infra.messaging.kafka.config;

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
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration
public class KafkaConfig {

	private static final long RETRY_INTERVAL_MS = 1_000L;
	private static final long MAX_RETRY_ATTEMPTS = 3L;
	private static final String TRUSTED_PACKAGES = "com.prompthub.order.*";

	private final String bootstrapServers;
	private final String groupId;
	private final String autoOffsetReset;
	private final boolean enableAutoCommit;

	public KafkaConfig(
		@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
		@Value("${spring.kafka.consumer.group-id}") String groupId,
		@Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
		@Value("${spring.kafka.consumer.enable-auto-commit:false}") boolean enableAutoCommit
	) {
		this.bootstrapServers = bootstrapServers;
		this.groupId = groupId;
		this.autoOffsetReset = autoOffsetReset;
		this.enableAutoCommit = enableAutoCommit;
	}

	@Bean
	public ProducerFactory<String, Object> producerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(properties);
	}

	@Bean
	public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	public ProducerFactory<String, String> stringProducerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new DefaultKafkaProducerFactory<>(properties);
	}

	@Bean
	public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<String, String> stringProducerFactory) {
		return new KafkaTemplate<>(stringProducerFactory);
	}

	@Bean
	public ConsumerFactory<String, Object> consumerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
		properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
		properties.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, TRUSTED_PACKAGES);
		return new DefaultKafkaConsumerFactory<>(properties);
	}

	@Bean
	public ConsumerFactory<String, String> paymentEventConsumerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
		properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(properties);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
		ConsumerFactory<String, Object> consumerFactory,
		DefaultErrorHandler kafkaErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(kafkaErrorHandler);
		return factory;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> paymentEventKafkaListenerContainerFactory(
		ConsumerFactory<String, String> paymentEventConsumerFactory,
		DefaultErrorHandler kafkaErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(paymentEventConsumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(kafkaErrorHandler);
		return factory;
	}

	@Bean
	public ConsumerFactory<String, String> productEventConsumerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
		properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(properties);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> productEventKafkaListenerContainerFactory(
		ConsumerFactory<String, String> productEventConsumerFactory,
		DefaultErrorHandler kafkaErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(productEventConsumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(kafkaErrorHandler);
		return factory;
	}

	@Bean
	public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
		);
		return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS));
	}
}
