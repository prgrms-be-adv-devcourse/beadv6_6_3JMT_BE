package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.gateway.external.RefundResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TossPaymentGatewayTest {

    private HttpServer server;
    private final AtomicReference<HttpExchange> capturedExchange = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/test-pg-key/cancel", exchange -> {
            capturedExchange.set(exchange);
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseBody = """
                {"status":"CANCELED","cancels":[{"canceledAt":"2026-07-13T10:00:00+09:00"}]}
                """;
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
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
    void refund_호출_시_cancelAmount와_refundId_기준_Idempotency_Key_전달() throws IOException {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            Retry.ofDefaults("test-confirm-retry")
        );
        UUID refundId = UUID.randomUUID();

        RefundResult result = gateway.refund("test-pg-key", refundId, 3_000);

        assertThat(result.refundedAt()).isNotNull();

        JsonNode requestJson = objectMapper.readTree(capturedBody.get());
        assertThat(requestJson.get("cancelAmount").asInt()).isEqualTo(3_000);

        String idempotencyKey = capturedExchange.get().getRequestHeaders().getFirst("Idempotency-Key");
        assertThat(idempotencyKey).isEqualTo("refund-" + refundId);
    }
}
