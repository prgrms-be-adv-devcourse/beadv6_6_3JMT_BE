package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.prompthub.payment.infrastructure.external.toss.TossRetryTestSupport.retryOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayCircuitBreakerTest {

    private HttpServer server;
    private final AtomicInteger callCount = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            String body = """
                {"code":"INTERNAL_SERVER_ERROR","message":"Toss 서버 오류"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
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
    void confirm_반복_5xx_실패로_서킷브레이커가_OPEN되면_이후_호출은_즉시_PG_UNAVAILABLE을_던진다() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordException(new TossFailurePredicate())
            .build();
        CircuitBreaker confirmCircuitBreaker = CircuitBreaker.of("test-confirm", config);
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            confirmCircuitBreaker,
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class);

        assertThat(confirmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callCountBeforeOpenState = callCount.get();

        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_UNAVAILABLE);

        assertThat(callCount.get()).isEqualTo(callCountBeforeOpenState);
    }
}
