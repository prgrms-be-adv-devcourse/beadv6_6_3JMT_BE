package com.prompthub.notification.infra.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notification.sse")
public record SseProperties(
    int maxConnectionsPerUser,
    long timeoutMillis,
    long heartbeatIntervalMillis
) {
}
