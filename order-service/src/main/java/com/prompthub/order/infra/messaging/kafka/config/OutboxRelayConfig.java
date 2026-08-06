package com.prompthub.order.infra.messaging.kafka.config;

import com.prompthub.order.infra.metrics.OutboxMonitorProperties;
import com.prompthub.order.infra.messaging.kafka.producer.OutboxRelayProperties;
import com.prompthub.order.infra.messaging.kafka.producer.OutboxRetryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    OutboxRelayProperties.class,
    OutboxRetryProperties.class,
    OutboxMonitorProperties.class
})
public class OutboxRelayConfig {
}
