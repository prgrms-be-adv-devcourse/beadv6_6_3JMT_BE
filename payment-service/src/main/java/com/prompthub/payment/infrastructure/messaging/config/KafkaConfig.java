package com.prompthub.payment.infrastructure.messaging.config;

import com.prompthub.payment.domain.exception.InvalidRefundStateException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * @EnableKafka는 명시하지 않는다 — Spring Boot 자동설정(KafkaAnnotationDrivenConfiguration)이 제공한다.
 * 그래야 KafkaAutoConfiguration을 제외한 JPA 슬라이스 테스트에서 리스너 컨테이너가 기동되지 않는다.
 */
@Configuration
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRY_ATTEMPTS = 3L;

    private final String bootstrapServers;

    public KafkaConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PaymentTopic.PAYMENT_EVENTS)
            .partitions(1)
            .replicas(1)
            .build();
    }

    // order-events 구독 전용 (String 수동 파싱) — 메시지 타입이 order 도메인 계약이라 JsonDeserializer 타입 헤더에 의존 불가.
    @Bean
    public ConsumerFactory<String, String> orderEventConsumerFactory() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        properties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        properties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> orderEventKafkaListenerContainerFactory(
        ConsumerFactory<String, String> orderEventConsumerFactory,
        DefaultErrorHandler kafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderEventConsumerFactory);
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
        DefaultErrorHandler errorHandler =
            new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS));
        // 재시도해도 결과가 달라지지 않는 영구 실패 — 즉시 DLT로 보낸다.
        // InvalidRefundStateException: 누적 환불액 초과(재시도 무의미).
        // DataIntegrityViolationException: 중복 환불 이벤트(유니크 인덱스 위반). 이 서비스는 PG 환불 호출을
        // DB 저장보다 먼저 실행하므로, 재시도가 일어나면 이미 성공한 PG 호출이 새 refundId로 반복 실행된다.
        errorHandler.addNotRetryableExceptions(InvalidRefundStateException.class, DataIntegrityViolationException.class);
        return errorHandler;
    }
}
