package com.prompthub.apigateway.logging;

import org.slf4j.event.Level;

import com.prompthub.apigateway.client.GatewayRole;

public record GatewayAccessLog(
        String eventType,
        String service,
        String requestId,
        String method,
        String path,
        String routeId,
        int status,
        long durationMs,
        boolean authenticated,
        GatewayRole userRole,
        String clientIp,
        String exceptionType,
        Level level
) {
}
