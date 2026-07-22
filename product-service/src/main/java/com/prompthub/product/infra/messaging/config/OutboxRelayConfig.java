package com.prompthub.product.infra.messaging.config;

import com.prompthub.product.infra.messaging.producer.OutboxRelayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfig {
}
