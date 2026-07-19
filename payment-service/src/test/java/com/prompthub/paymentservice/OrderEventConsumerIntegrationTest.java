package com.prompthub.paymentservice;

import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepository;
import com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepository;
import com.prompthub.paymentservice.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class OrderEventConsumerIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "order-events";

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @BeforeEach
    void clean() {
        refundJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    @Test
    void ORDER_REFUND_REQUESTED_수신_시_부분환불_처리() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();

        Payment payment = Payment.create(orderId, userId, "pg-key-refund-1", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(refundedAt));

        send(orderId.toString(), orderRefundRequestedJson(orderId, orderProductId, userId, 4_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });

        long refundCount = refundJpaRepository.findAll().stream()
            .filter(r -> r.getPaymentId().equals(payment.getId()))
            .count();
        assertThat(refundCount).isEqualTo(1);
    }

    @Test
    void ORDER_REFUND_REQUESTED_외_이벤트타입은_무시() {
        UUID ignoredOrderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        UUID orderId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, buyerId, "pg-key-refund-2", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(OffsetDateTime.now()));

        // 단일 파티션 순서 보장: 무시 대상 → 처리 대상 순으로 발행. 뒤 메시지가 처리되면
        // 컨슈머가 앞의 무시 대상 메시지를 이미 지나쳤다는 의미다.
        send(ignoredOrderId.toString(), orderPaidJson(ignoredOrderId, buyerId));
        send(orderId.toString(), orderRefundRequestedJson(orderId, orderProductId, buyerId, 4_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });
    }

    private String orderRefundRequestedJson(UUID orderId, UUID orderProductId, UUID buyerId, int refundAmount) {
        return String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"ORDER_REFUND_REQUESTED\",\"occurredAt\":\"2026-07-13T10:00:00\","
                + "\"aggregateType\":\"ORDER\",\"aggregateId\":\"%s\",\"payload\":{"
                + "\"orderId\":\"%s\",\"orderProductId\":\"%s\",\"buyerId\":\"%s\","
                + "\"refundAmount\":%d,\"requestedAt\":\"2026-07-13T10:00:00\"}}",
            UUID.randomUUID(), orderId, orderId, orderProductId, buyerId, refundAmount);
    }

    private String orderPaidJson(UUID orderId, UUID buyerId) {
        // order-service의 enveloped ORDER_PAID를 흉내 — 최상위 eventType만으로 무시되어야 함
        return String.format(
            "{\"eventType\":\"ORDER_PAID\",\"aggregateId\":\"%s\",\"payload\":{\"orderId\":\"%s\",\"buyerId\":\"%s\"}}",
            orderId, orderId, buyerId);
    }

    private void send(String key, String value) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(TOPIC, key, value));
            producer.flush();
        }
    }
}
