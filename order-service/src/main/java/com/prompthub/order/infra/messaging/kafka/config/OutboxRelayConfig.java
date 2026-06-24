package com.prompthub.order.infra.messaging.kafka.config;

import com.prompthub.order.infra.messaging.kafka.producer.OutboxRelayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfig {
}
