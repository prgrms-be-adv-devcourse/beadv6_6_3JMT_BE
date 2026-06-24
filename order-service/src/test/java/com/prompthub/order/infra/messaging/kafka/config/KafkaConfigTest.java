package com.prompthub.order.infra.messaging.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

class KafkaConfigTest {

	private static final String BOOTSTRAP_SERVERS = "localhost:9092";

	private final KafkaConfig kafkaConfig = new KafkaConfig(
		BOOTSTRAP_SERVERS,
		"order-service",
		"earliest",
		false
	);

	@Test
	@DisplayName("프로듀서 팩토리는 키를 String, 값을 JSON으로 직렬화한다")
	void producerFactory_usesStringKeysAndJsonValues() {
		ProducerFactory<String, Object> producerFactory = kafkaConfig.producerFactory();

		assertThat(producerFactory).isInstanceOf(DefaultKafkaProducerFactory.class);

		Map<String, Object> properties = ((DefaultKafkaProducerFactory<String, Object>) producerFactory)
			.getConfigurationProperties();
		assertThat(properties)
			.containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
			.containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
			.containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
	}

	@Test
	@DisplayName("카프카 템플릿은 설정된 프로듀서 팩토리를 사용한다")
	void kafkaTemplate_usesConfiguredProducerFactory() {
		ProducerFactory<String, Object> producerFactory = kafkaConfig.producerFactory();

		KafkaTemplate<String, Object> kafkaTemplate = kafkaConfig.kafkaTemplate(producerFactory);

		assertThat(kafkaTemplate.getProducerFactory()).isSameAs(producerFactory);
	}

	@Test
	@DisplayName("컨슈머 팩토리는 수동 커밋과 에러 핸들링 역직렬화기를 사용한다")
	void consumerFactory_usesManualCommitAndErrorHandlingDeserializers() {
		ConsumerFactory<String, Object> consumerFactory = kafkaConfig.consumerFactory();

		assertThat(consumerFactory).isInstanceOf(DefaultKafkaConsumerFactory.class);

		Map<String, Object> properties = ((DefaultKafkaConsumerFactory<String, Object>) consumerFactory)
			.getConfigurationProperties();
		assertThat(properties)
			.containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
			.containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "order-service")
			.containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
			.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
			.containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class)
			.containsEntry(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.prompthub.order.*");
	}

	@Test
	@DisplayName("결제 이벤트 컨슈머 팩토리는 값을 String으로 역직렬화한다")
	void paymentEventConsumerFactory_usesStringValues() {
		ConsumerFactory<String, String> consumerFactory = kafkaConfig.paymentEventConsumerFactory();

		assertThat(consumerFactory).isInstanceOf(DefaultKafkaConsumerFactory.class);

		Map<String, Object> properties = ((DefaultKafkaConsumerFactory<String, String>) consumerFactory)
			.getConfigurationProperties();
		assertThat(properties)
			.containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
			.containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "order-service")
			.containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
			.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
			.containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
	}

	@Test
	@DisplayName("카프카 리스너 컨테이너 팩토리는 수동 Ack와 공통 에러 핸들러를 사용한다")
	void kafkaListenerContainerFactory_usesManualAckAndCommonErrorHandler() {
		ConsumerFactory<String, Object> consumerFactory = kafkaConfig.consumerFactory();
		DefaultErrorHandler errorHandler = kafkaConfig.kafkaErrorHandler(kafkaConfig.kafkaTemplate(kafkaConfig.producerFactory()));

		ConcurrentKafkaListenerContainerFactory<String, Object> factory =
			kafkaConfig.kafkaListenerContainerFactory(consumerFactory, errorHandler);

		assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
	}

	@Test
	@DisplayName("결제 이벤트 카프카 리스너 컨테이너 팩토리는 수동 Ack와 공통 에러 핸들러를 사용한다")
	void paymentEventKafkaListenerContainerFactory_usesManualAckAndCommonErrorHandler() {
		ConsumerFactory<String, String> consumerFactory = kafkaConfig.paymentEventConsumerFactory();
		DefaultErrorHandler errorHandler = kafkaConfig.kafkaErrorHandler(kafkaConfig.kafkaTemplate(kafkaConfig.producerFactory()));

		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			kafkaConfig.paymentEventKafkaListenerContainerFactory(consumerFactory, errorHandler);

		assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
	}

	@Test
	@DisplayName("상품 이벤트 컨슈머 팩토리는 값을 String으로 역직렬화한다")
	void productEventConsumerFactory_usesStringValues() {
		ConsumerFactory<String, String> consumerFactory = kafkaConfig.productEventConsumerFactory();

		assertThat(consumerFactory).isInstanceOf(DefaultKafkaConsumerFactory.class);

		Map<String, Object> properties = ((DefaultKafkaConsumerFactory<String, String>) consumerFactory)
			.getConfigurationProperties();
		assertThat(properties)
			.containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
			.containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "order-service")
			.containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
			.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
			.containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class)
			.containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
	}

	@Test
	@DisplayName("상품 이벤트 카프카 리스너 컨테이너 팩토리는 수동 Ack와 공통 에러 핸들러를 사용한다")
	void productEventKafkaListenerContainerFactory_usesManualAckAndCommonErrorHandler() {
		ConsumerFactory<String, String> consumerFactory = kafkaConfig.productEventConsumerFactory();
		DefaultErrorHandler errorHandler = kafkaConfig.kafkaErrorHandler(kafkaConfig.kafkaTemplate(kafkaConfig.producerFactory()));

		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			kafkaConfig.productEventKafkaListenerContainerFactory(consumerFactory, errorHandler);

		assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
	}

	@Test
	@DisplayName("카프카 에러 핸들러는 DLT 복구를 위한 DefaultErrorHandler를 반환한다")
	void kafkaErrorHandler_isDefaultErrorHandlerForDltRecovery() {
		DefaultErrorHandler errorHandler = kafkaConfig.kafkaErrorHandler(
			kafkaConfig.kafkaTemplate(kafkaConfig.producerFactory())
		);

		assertThat(errorHandler).isNotNull();
	}
}
