package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentApprovedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage;
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

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        Payment payment = event.payment();
        PaymentApprovedMessage message = new PaymentApprovedMessage(
            "payment.approved",
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getApprovedAmount(),
            payment.getApprovedAt() != null ? payment.getApprovedAt().toString() : null
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_APPROVED, payment.getOrderId().toString(), message);
    }

    // TransactionTemplate.execute() 완료 후 직접 호출 — Spring Boot 4.1 중첩 @TransactionalEventListener 제한 우회
    public void publishRefunded(Payment payment, Refund refund) {
        PaymentRefundedMessage message = new PaymentRefundedMessage(
            "payment.refunded",
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getRefundAmount(),
            payment.getRefundedAt() != null ? payment.getRefundedAt().toString() : null
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_REFUNDED, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("환불 Kafka 메시지 발행 실패 — paymentId={}, cause={}",
                        payment.getId(), ex.getMessage());
                } else {
                    log.info("환불 Kafka 메시지 발행 성공 — paymentId={}, partition={}, offset={}",
                        payment.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }
}
