package com.prompthub.paymentservice;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepository;
import com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepository;
import com.prompthub.paymentservice.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RefundPaymentIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    RestTemplate restTemplate;

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @BeforeEach
    void setUpRestTemplate() {
        refundJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void 환불_정상_플로우_DB_REFUNDED_Kafka_메시지_수신() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();
        OffsetDateTime refundedAt = OffsetDateTime.now();

        when(paymentGateway.confirm(anyString(), any(), anyInt()))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", approvedAt));
        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(refundedAt));

        // 결제 승인 먼저
        승인_요청(orderId, userId, 10_000);

        Payment payment = paymentJpaRepository.findByIdempotencyKey("pay-" + orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        // Kafka 컨슈머 준비
        KafkaConsumer<String, String> consumer = 컨슈머_생성("refund-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_REFUNDED, 0);
        consumer.assign(java.util.List.of(partition));
        // seekToBeginning은 lazy — 첫 poll() 시 적용됨.
        // poll(ZERO)로 메타데이터를 먼저 초기화해야 seekToBeginning이 올바른 offset으로 적용됨.
        consumer.poll(java.time.Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        // 환불 요청
        ResponseEntity<Map> refundResponse = 환불_요청(payment.getId(), userId);
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refundResponse.getBody()).isNotNull();
        assertThat((Boolean) refundResponse.getBody().get("success")).isTrue();

        // DB 상태 최종 확인
        Payment refunded = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.getRefundedAt()).isNotNull();

        // 다른 테스트에서 동일 토픽에 메시지가 발행됐을 수 있으므로
        // getSingleRecord() 대신 직접 폴링으로 orderId key 메시지를 탐색
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(java.time.Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key())) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("10초 내 payment.refunded Kafka 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }
    }

    @Test
    void PG_환불_실패_시_PAID_복원() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(paymentGateway.confirm(anyString(), any(), anyInt()))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", OffsetDateTime.now()));
        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null));

        승인_요청(orderId, userId, 10_000);
        Payment payment = paymentJpaRepository.findByIdempotencyKey("pay-" + orderId).orElseThrow();

        환불_요청(payment.getId(), userId);

        // AFTER_COMMIT 처리 완료를 조건부 대기
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                Payment restored = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(restored.getStatus()).isEqualTo(PaymentStatus.PAID);
            });
    }

    @Test
    void 타인_결제_환불_시_403() {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        when(paymentGateway.confirm(anyString(), any(), anyInt()))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", OffsetDateTime.now()));

        승인_요청(orderId, ownerId, 10_000);
        Payment payment = paymentJpaRepository.findByIdempotencyKey("pay-" + orderId).orElseThrow();

        ResponseEntity<Map> response = 환불_요청(payment.getId(), otherId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("code")).isEqualTo("PAY006");
    }

    private void 승인_요청(UUID orderId, UUID userId, int amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId.toString());
        headers.set("X-User-Role", "BUYER");
        Map<String, Object> body = Map.of(
            "paymentKey", "toss-key-" + orderId,
            "orderId", orderId.toString(),
            "amount", amount
        );
        restTemplate.exchange(url("/api/v1/payments/confirm"), HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> 환불_요청(UUID paymentId, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-User-Role", "BUYER");
        return restTemplate.exchange(
            url("/api/v1/payments/" + paymentId + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, headers),
            Map.class
        );
    }

    private KafkaConsumer<String, String> 컨슈머_생성(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
