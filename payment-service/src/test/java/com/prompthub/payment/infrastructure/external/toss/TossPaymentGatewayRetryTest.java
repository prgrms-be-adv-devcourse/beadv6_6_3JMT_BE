package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.prompthub.payment.infrastructure.external.toss.TossRetryTestSupport.retryOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayRetryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ScheduledExecutorService scheduler;

    private static final String SUCCESS_BODY = """
        {"paymentKey":"pg-key","orderId":"order-id","method":"카드","totalAmount":1000,\
        "approvedAt":"2026-07-13T10:00:00+09:00","requestedAt":"2026-07-13T09:59:00+09:00","status":"DONE"}
        """;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void 연결_거부_후_재시도로_성공한다() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/payments/confirm", exchange -> {
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // 서버를 아직 start()하지 않는다 — 첫 시도는 연결 거부(ConnectException)로 실패시키기 위함

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            try {
                server.start();
            } catch (Exception ignored) {
                // 테스트 종료 후 호출될 수 있는 경합은 무시
            }
        }, 200, TimeUnit.MILLISECONDS);

        String baseUrl = "http://localhost:" + port + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        ConfirmResult result = gateway.confirm("pg-key", UUID.randomUUID(), 1_000);

        assertThat(result.approvedAmount()).isEqualTo(1000);
    }

    @Test
    void PG_4xx_오류는_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            String body = """
                {"code":"EXCEED_MAX_DAILY_PAYMENT_COUNT","message":"한도 초과"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_FAILED);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void Bulkhead_포화는_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Bulkhead exhaustedBulkhead = Bulkhead.of("test-confirm-bulkhead",
            BulkheadConfig.custom().maxConcurrentCalls(0).maxWaitDuration(Duration.ZERO).build());

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            exhaustedBulkhead,
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_BUSY);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void RateLimiter_거절은_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        RateLimiter exhaustedRateLimiter = RateLimiter.of("test-confirm-rate-limiter",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        exhaustedRateLimiter.acquirePermission();

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            exhaustedRateLimiter,
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_RATE_LIMITED);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void Idempotency_Key_헤더에_confirm_접두사와_paymentKey를_담는다() throws IOException {
        AtomicReference<HttpExchange> capturedExchange = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            capturedExchange.set(exchange);
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        gateway.confirm("pg-key-123", UUID.randomUUID(), 1_000);

        String idempotencyKey = capturedExchange.get().getRequestHeaders().getFirst("Idempotency-Key");
        assertThat(idempotencyKey).isEqualTo("confirm-pg-key-123");
    }

    @Test
    void 재시도_소진_실패는_CircuitBreaker에_1건으로만_기록된다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
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

        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordException(new TossFailurePredicate())
                .build();
        CircuitBreaker confirmCircuitBreaker = CircuitBreaker.of("test-confirm", cbConfig);
        Retry fastRetry = Retry.of("test-confirm-retry", RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(10))
            .retryOnException(new TossRetryPredicate())
            .build());

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            confirmCircuitBreaker,
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            fastRetry
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class);

        // confirm() 2번 호출, 각 호출마다 원 시도+재시도 = 총 HTTP 호출 4번
        assertThat(callCount.get()).isEqualTo(4);
        // 그러나 CircuitBreaker 슬라이딩 윈도우(크기 2)에는 confirm() 호출 단위로 2건만 기록되어 OPEN
        assertThat(confirmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
