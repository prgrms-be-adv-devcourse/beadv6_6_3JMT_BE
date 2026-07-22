package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TossPaymentGatewayBulkheadTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CountDownLatch releaseLatch;

    @BeforeEach
    void setUp() throws IOException {
        releaseLatch = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments/confirm", exchange -> {
            try {
                releaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
    void confirm_동시호출이_상한을_초과하면_초과분은_즉시_PG_BUSY를_던진다() throws InterruptedException {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        Bulkhead confirmBulkhead = Bulkhead.of(
            "test-confirm-bulkhead",
            BulkheadConfig.custom().maxConcurrentCalls(2).maxWaitDuration(Duration.ZERO).build()
        );
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            confirmBulkhead
        );

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<ConfirmResult>> futures = List.of(
            executor.submit(() -> gateway.confirm("pg-key-1", UUID.randomUUID(), 1_000)),
            executor.submit(() -> gateway.confirm("pg-key-2", UUID.randomUUID(), 1_000)),
            executor.submit(() -> gateway.confirm("pg-key-3", UUID.randomUUID(), 1_000))
        );

        // 3개 호출이 전부 permit 획득을 시도할 시간을 준 뒤(2개는 permit 보유, 1개는 즉시 거절된 상태) 응답 릴리즈
        Thread.sleep(300);
        releaseLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        AtomicInteger busyCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        for (Future<ConfirmResult> future : futures) {
            try {
                future.get();
                successCount.incrementAndGet();
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof PaymentGatewayException exception
                    && exception.getErrorCode() == PaymentErrorCode.PG_BUSY) {
                    busyCount.incrementAndGet();
                } else {
                    throw new AssertionError("예상치 못한 예외", cause);
                }
            }
        }

        assertThat(successCount.get()).isEqualTo(2);
        assertThat(busyCount.get()).isEqualTo(1);
    }
}
