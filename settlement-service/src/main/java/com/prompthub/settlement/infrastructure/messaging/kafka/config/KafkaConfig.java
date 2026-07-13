package com.prompthub.settlement.infrastructure.messaging.kafka.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaConfig {

	private final String bootstrapServers;

	public KafkaConfig(
		@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
	) {
		this.bootstrapServers = bootstrapServers;
	}

	@Bean("outboxProducerFactory")
	public ProducerFactory<String, String> outboxProducerFactory() {
		Map<String, Object> properties = new HashMap<>();
		properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.ACKS_CONFIG, "all");
		return new DefaultKafkaProducerFactory<>(properties);
	}

	@Bean("outboxKafkaTemplate")
	public KafkaTemplate<String, String> outboxKafkaTemplate(
		@Qualifier("outboxProducerFactory") ProducerFactory<String, String> producerFactory
	) {
		return new KafkaTemplate<>(producerFactory);
	}
}
