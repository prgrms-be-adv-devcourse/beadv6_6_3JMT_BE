package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.prompthub.payment.infrastructure.external.toss.TossRetryTestSupport.retryOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayRateLimiterTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments/confirm", exchange -> {
            String body = """
                {"paymentKey":"pg-key","orderId":"order-id","method":"카드","totalAmount":1000,\
                "approvedAt":"2026-07-13T10:00:00+09:00","requestedAt":"2026-07-13T09:59:00+09:00","status":"DONE"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void confirm_유량_상한을_초과하면_초과분은_즉시_PG_RATE_LIMITED를_던진다() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        RateLimiter confirmRateLimiter = RateLimiter.of(
            "test-confirm-rate-limiter",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build()
        );
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            confirmRateLimiter,
            retryOf("test-confirm-retry")
        );

        ConfirmResult first = gateway.confirm("pg-key-1", UUID.randomUUID(), 1_000);

        assertThatThrownBy(() -> gateway.confirm("pg-key-2", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_RATE_LIMITED);

        assertThat(first).isNotNull();
    }
}
