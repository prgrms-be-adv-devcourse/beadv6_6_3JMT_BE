package com.prompthub.order.infra.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.outbox-monitor")
public record OutboxMonitorProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("60000") long fixedDelayMs,
    @DefaultValue("1") long failedCountThreshold,
    @DefaultValue("300000") long oldestUnpublishedThresholdMs
) {

    @ConstructorBinding
    public OutboxMonitorProperties {
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("fixedDelayMs must be positive");
        }
        if (failedCountThreshold <= 0) {
            throw new IllegalArgumentException("failedCountThreshold must be positive");
        }
        if (oldestUnpublishedThresholdMs <= 0) {
            throw new IllegalArgumentException("oldestUnpublishedThresholdMs must be positive");
        }
    }
}
