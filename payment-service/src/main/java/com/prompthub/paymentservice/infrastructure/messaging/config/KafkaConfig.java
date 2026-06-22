package com.prompthub.paymentservice.infrastructure.messaging.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {
    // 이벤트 확정 후 NewTopic Bean 추가 예정
    // ex)
    // @Bean
    // public NewTopic paymentCompletedTopic() {
    //     return TopicBuilder.name(PaymentTopic.PAYMENT_COMPLETED)
    //             .partitions(1)
    //             .replicas(1)
    //             .build();
    // }
}
