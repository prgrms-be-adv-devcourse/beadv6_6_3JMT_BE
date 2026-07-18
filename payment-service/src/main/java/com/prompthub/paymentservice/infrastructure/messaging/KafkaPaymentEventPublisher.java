package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.common.event.EventMessage;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.event.PaymentFailedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentApprovedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentFailedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundFailedMessage;
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
            payment.getOrderId(),
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
        PaymentFailedMessage payload = new PaymentFailedMessage(payment.getOrderId());
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        PaymentRefundedMessage payload = new PaymentRefundedMessage(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
            refund.getRefundAmount(),
            payment.getStatus().name(),
            toKstString(payment.getRefundedAt())
        );
        EventMessage<PaymentRefundedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_REFUNDED.code(),
            toKst(payment.getRefundedAt()),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("환불 완료 Kafka 메시지 발행 실패 — paymentId={}, refundId={}, cause={}",
                        payment.getId(), refund.getId(), ex.getMessage());
                } else {
                    log.info("환불 완료 Kafka 메시지 발행 성공 — paymentId={}, refundId={}, partition={}, offset={}",
                        payment.getId(), refund.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        OffsetDateTime failedAt = OffsetDateTime.now();
        PaymentRefundFailedMessage payload = new PaymentRefundFailedMessage(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
            refund.getRefundAmount(),
            payment.getStatus().name(),
            event.failureReason(),
            toKstString(failedAt)
        );
        EventMessage<PaymentRefundFailedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_REFUND_FAILED.code(),
            toKst(failedAt),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("환불 실패 Kafka 메시지 발행 실패 — paymentId={}, refundId={}, cause={}",
                        payment.getId(), refund.getId(), ex.getMessage());
                } else {
                    log.info("환불 실패 Kafka 메시지 발행 성공 — paymentId={}, refundId={}, partition={}, offset={}",
                        payment.getId(), refund.getId(),
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
