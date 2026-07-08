package com.prompthub.paymentservice.infrastructure.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PaymentTopic.PAYMENT_EVENTS)
            .partitions(1)
            .replicas(1)
            .build();
    }
}
