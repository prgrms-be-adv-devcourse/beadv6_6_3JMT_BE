package com.prompthub.apigateway.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import static com.prompthub.apigateway.logging.GatewayLogConstants.ACCESS_MESSAGE;

@Component
public class GatewayAccessLogWriter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogWriter.class);

    public void write(GatewayAccessLog event) {
        LoggingEventBuilder builder = switch (event.level()) {
            case ERROR -> log.atError();
            case WARN -> log.atWarn();
            default -> log.atInfo();
        };

        builder
                .addKeyValue("eventType", event.eventType())
                .addKeyValue("service", event.service())
                .addKeyValue("requestId", event.requestId())
                .addKeyValue("method", event.method())
                .addKeyValue("path", event.path())
                .addKeyValue("routeId", event.routeId())
                .addKeyValue("status", event.status())
                .addKeyValue("durationMs", event.durationMs())
                .addKeyValue("authenticated", event.authenticated())
                .addKeyValue("clientIp", event.clientIp());

        if (event.userRole() != null) {
            builder.addKeyValue("userRole", event.userRole().name());
        }
        if (event.exceptionType() != null) {
            builder.addKeyValue("exceptionType", event.exceptionType());
        }
        builder.log(ACCESS_MESSAGE);
    }
}
