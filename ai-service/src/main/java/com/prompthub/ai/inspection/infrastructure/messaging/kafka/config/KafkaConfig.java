package com.prompthub.ai.inspection.infrastructure.messaging.kafka.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 프로듀서/KafkaTemplate은 Boot autoconfig에 위임한다(serializer는 application-*.yml
 * spring.kafka.producer.* 참조). 컨슈머만 product-events 파싱 요구(String 수동 파싱)에 맞춰 오버라이드한다.
 */
@Configuration
public class KafkaConfig {

	private final String bootstrapServers;

	public KafkaConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
		this.bootstrapServers = bootstrapServers;
	}

	@Bean
	public ConsumerFactory<String, String> productEventConsumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
		config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> productEventContainerFactory(
		ConsumerFactory<String, String> productEventConsumerFactory,
		DefaultErrorHandler productEventErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(productEventConsumerFactory);
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setCommonErrorHandler(productEventErrorHandler);
		return factory;
	}

	// 처리 실패 이벤트는 재시도 후 원본 토픽의 DLT(`product-events.DLT`)로 보낸다. (kafka-event.md §7)
	@Bean
	public DefaultErrorHandler productEventErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
		);
		return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
	}
}
