package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.common.event.EventMessage;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.event.PaymentFailedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentApprovedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentFailedMessage;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    // payment-events는 order-service와의 상관관계 유지를 위해 aggregateType을 ORDER로,
    // aggregateId를 orderId로 고정한다(공통 이벤트 규칙 §9·§14).
    private static final String AGGREGATE_TYPE_ORDER = "ORDER";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        Payment payment = event.payment();
        PaymentApprovedMessage payload = new PaymentApprovedMessage(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getApprovedAmount(),
            toKstString(payment.getApprovedAt())
        );
        EventMessage<PaymentApprovedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_APPROVED.code(),
            toKst(payment.getApprovedAt()),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("결제 승인 Kafka 메시지 발행 실패 — paymentId={}, cause={}",
                        payment.getId(), ex.getMessage());
                } else {
                    log.info("결제 승인 Kafka 메시지 발행 성공 — paymentId={}, partition={}, offset={}",
                        payment.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        Payment payment = event.payment();
        PaymentFailedMessage payload = new PaymentFailedMessage(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId()
        );
        EventMessage<PaymentFailedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_FAILED.code(),
            toKst(payment.getFailedAt()),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("결제 실패 Kafka 메시지 발행 실패 — paymentId={}, cause={}",
                        payment.getId(), ex.getMessage());
                } else {
                    log.info("결제 실패 Kafka 메시지 발행 성공 — paymentId={}, partition={}, offset={}",
                        payment.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }

    private String toKstString(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.withOffsetSameInstant(KST).toString() : null;
    }

    private LocalDateTime toKst(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.withOffsetSameInstant(KST).toLocalDateTime() : null;
    }
}
