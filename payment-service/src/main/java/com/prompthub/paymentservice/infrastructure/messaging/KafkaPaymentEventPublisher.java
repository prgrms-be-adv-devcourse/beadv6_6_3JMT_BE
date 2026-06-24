package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentApprovedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        PaymentRefundedMessage message = new PaymentRefundedMessage(
            "payment.refunded",
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getRefundAmount(),
            payment.getRefundedAt() != null ? payment.getRefundedAt().toString() : null
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_REFUNDED, payment.getOrderId().toString(), message);
    }
}
