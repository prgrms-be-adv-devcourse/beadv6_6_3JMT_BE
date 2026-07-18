package com.prompthub.paymentservice;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.OrderGateway;
import com.prompthub.paymentservice.application.gateway.external.OrderPaymentInfo;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmPaymentIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    RestTemplate restTemplate;

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @MockitoBean
    OrderGateway orderGateway;

    @BeforeEach
    void setUpRestTemplate() {
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

    private ResponseEntity<Map> confirm(UUID orderId, UUID userId, String paymentKey, int amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId.toString());
        Map<String, Object> body = Map.of(
            "paymentKey", paymentKey,
            "orderId", orderId.toString(),
            "amount", amount
        );
        return restTemplate.exchange(
            url("/api/v2/payments/confirm"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void 결제_승인_정상_플로우_DB_PAID_Kafka_메시지_수신() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));
        when(paymentGateway.confirm(anyString(), eq(orderId), eq(10_000)))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", approvedAt));

        Map<String, Object> consumerProps = buildConsumerProps(kafka.getBootstrapServers(), "integration-test-group");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        // seekToBeginning은 lazy — poll(ZERO)로 메타데이터 먼저 초기화 후 적용
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        ResponseEntity<Map> response = confirm(orderId, userId, "toss-integration-key", 10_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat((Boolean) response.getBody().get("success")).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        UUID paymentId = UUID.fromString((String) data.get("paymentId"));
        Payment payment = paymentJpaRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
        assertThat(payment.getUserId()).isEqualTo(userId);

        // 다른 테스트의 메시지가 남아있을 수 있으므로 orderId key로 직접 탐색
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key())) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("10초 내 payment-events Kafka 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }
    }

    @Test
    void PG_결제_실패_시_payment_failed_수신_및_FAILED_저장() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));
        when(paymentGateway.confirm(anyString(), eq(orderId), eq(10_000)))
            .thenThrow(new PaymentGatewayException(
                PaymentErrorCode.PAYMENT_FAILED, "REJECT", "카드 거절", null, "{}"));

        KafkaConsumer<String, String> consumer =
            new KafkaConsumer<>(buildConsumerProps(kafka.getBootstrapServers(), "failed-test-group"));
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        ResponseEntity<Map> response = confirm(orderId, userId, "toss-failed-key", 10_000);

        assertThat(response.getStatusCode().value()).isEqualTo(422);

        Payment payment = paymentJpaRepository.findAll().stream()
            .filter(p -> p.getOrderId().equals(orderId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Payment not found for orderId=" + orderId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_FAILED")) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("10초 내 PAYMENT_FAILED Kafka 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }
    }

    @Test
    void 금액_불일치_시_400_AMOUNT_MISMATCH_및_결제시도_미기록() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));

        ResponseEntity<Map> response = confirm(orderId, userId, "toss-mismatch-key", 9_000);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("PAY012");

        boolean paymentExists = paymentJpaRepository.findAll().stream()
            .anyMatch(p -> p.getOrderId().equals(orderId));
        assertThat(paymentExists).isFalse();

        verify(paymentGateway, never()).confirm(anyString(), any(), anyInt());
    }

    @Test
    void 금액_불일치_후_올바른_금액으로_재시도_시_정상_승인() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));
        when(paymentGateway.confirm(anyString(), eq(orderId), eq(10_000)))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", approvedAt));

        ResponseEntity<Map> mismatch = confirm(orderId, userId, "toss-mismatch-retry-key-1", 9_000);
        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> success = confirm(orderId, userId, "toss-mismatch-retry-key-2", 10_000);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 실패한_주문_재결제_시도_시_409_DUPLICATE_PAYMENT() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));
        when(paymentGateway.confirm(anyString(), eq(orderId), eq(10_000)))
            .thenThrow(new PaymentGatewayException(
                PaymentErrorCode.PAYMENT_FAILED, "REJECT", "카드 거절", null, "{}"));

        ResponseEntity<Map> first = confirm(orderId, userId, "toss-retry-key-1", 10_000);
        assertThat(first.getStatusCode().value()).isEqualTo(422);

        ResponseEntity<Map> second = confirm(orderId, userId, "toss-retry-key-2", 10_000);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().get("code")).isEqualTo("PAY002");
    }

    private Map<String, Object> buildConsumerProps(String bootstrapServers, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }
}
