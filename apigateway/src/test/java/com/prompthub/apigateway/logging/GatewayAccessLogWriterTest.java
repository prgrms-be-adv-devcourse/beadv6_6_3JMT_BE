package com.prompthub.apigateway.logging;

import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.prompthub.apigateway.client.GatewayRole;

import static com.prompthub.apigateway.logging.GatewayLogConstants.ACCESS_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayAccessLogWriterTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private GatewayAccessLogWriter writer;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GatewayAccessLogWriter.class);
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        writer = new GatewayAccessLogWriter();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 모든_access_필드를_key_value로_기록한다() {
        writer.write(event(Level.WARN, GatewayRole.SELLER, "IllegalStateException"));

        ILoggingEvent logged = appender.list.get(0);
        Map<String, Object> fields = fields(logged);

        assertThat(logged.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        assertThat(logged.getFormattedMessage()).isEqualTo(ACCESS_MESSAGE);
        assertThat(fields)
                .containsEntry("eventType", "GATEWAY_ACCESS")
                .containsEntry("service", "apigateway")
                .containsEntry("requestId", "request-1")
                .containsEntry("method", "GET")
                .containsEntry("path", "/api/v2/orders")
                .containsEntry("routeId", "order-service")
                .containsEntry("status", 403)
                .containsEntry("durationMs", 12L)
                .containsEntry("authenticated", true)
                .containsEntry("userRole", "SELLER")
                .containsEntry("clientIp", "192.0.2.20")
                .containsEntry("exceptionType", "IllegalStateException");
        assertThat(fields).doesNotContainKeys(
                "level", "Authorization", "Cookie", "userId", "query", "requestBody", "responseBody");
    }

    @Test
    void null인_선택_필드는_기록하지_않는다() {
        writer.write(event(Level.INFO, null, null));

        Map<String, Object> fields = fields(appender.list.get(0));

        assertThat(fields).doesNotContainKeys("userRole", "exceptionType");
    }

    @Test
    void 모델의_level로_SLF4J_level을_선택한다() {
        writer.write(event(Level.INFO, null, null));
        writer.write(event(Level.WARN, null, null));
        writer.write(event(Level.ERROR, null, null));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(
                        ch.qos.logback.classic.Level.INFO,
                        ch.qos.logback.classic.Level.WARN,
                        ch.qos.logback.classic.Level.ERROR);
    }

    private static GatewayAccessLog event(
            Level level,
            GatewayRole role,
            String exceptionType) {
        return new GatewayAccessLog(
                "GATEWAY_ACCESS",
                "apigateway",
                "request-1",
                "GET",
                "/api/v2/orders",
                "order-service",
                403,
                12L,
                true,
                role,
                "192.0.2.20",
                exceptionType,
                level
        );
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
