package com.prompthub.payment;

import com.prompthub.payment.application.gateway.external.PaymentGateway;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.prompthub.payment.application.gateway.external.RefundResult;
import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import com.prompthub.payment.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.payment.infrastructure.persistence.PaymentJpaRepository;
import com.prompthub.payment.infrastructure.persistence.RefundJpaRepository;
import com.prompthub.payment.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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

class PartialRefundIntegrationTest extends AbstractIntegrationTest {

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
    void 두번의_부분환불_누적으로_전액_소진_및_Kafka_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-cumulative", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 6_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 4_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(2));

        Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PAID);
        int totalRefunded = refundJpaRepository.findAll().stream().mapToInt(r -> r.getRefundAmount()).sum();
        assertThat(totalRefunded).isEqualTo(10_000);

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            int foundCount = 0;
            while (foundCount < 2 && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUNDED")) {
                        foundCount++;
                    }
                }
            }
            assertThat(foundCount).withFailMessage("PAYMENT_REFUNDED 메시지 2건 수신 실패").isEqualTo(2);
        } finally {
            consumer.close();
        }
    }

    @Test
    void 동일_상품_재환불_두_refundRequestId_모두_성공() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-re-refund", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 3_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 2_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(2));

        Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 동일_refundRequestId_재전송_시_한번만_처리() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-dedup", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        String message = json(orderId, refundRequestId, 3_000);
        send(orderId.toString(), message);
        send(orderId.toString(), message);

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));

        // 두 메시지가 모두 소비될 시간을 확보한 뒤 refund row가 1건만 있는지 확인
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));
    }

    @Test
    void 과환불_시도_시_예외_없이_FAILED_기록_및_실패_이벤트_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-over-refund", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-over-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 12_000));

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUND_FAILED")) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("PAYMENT_REFUND_FAILED 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }

        Payment unchanged = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(refundJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void PG_환불_실패_시_Payment_상태_불변_및_실패_이벤트_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-fail", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null));

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-fail-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), 4_000));

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUND_FAILED")) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("PAYMENT_REFUND_FAILED 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }

        Payment unchanged = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private String json(UUID orderId, UUID refundRequestId, int refundAmount) {
        return String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"ORDER_REFUND_REQUESTED\",\"occurredAt\":\"2026-07-13T10:00:00\","
                + "\"aggregateType\":\"ORDER\",\"aggregateId\":\"%s\",\"payload\":{"
                + "\"orderId\":\"%s\",\"refundRequestId\":\"%s\","
                + "\"refundAmount\":%d,\"requestedAt\":\"2026-07-13T10:00:00\"}}",
            UUID.randomUUID(), orderId, orderId, refundRequestId, refundAmount);
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
