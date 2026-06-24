package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.TossRefundResult;
import com.prompthub.paymentservice.application.gateway.persistence.PaymentRepository;
import com.prompthub.paymentservice.application.gateway.persistence.RefundRepository;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundRequestedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefundEventHandler {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundRequested(PaymentRefundRequestedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId()).orElseThrow();
        Refund refund = refundRepository.findByPaymentId(event.paymentId()).orElseThrow();

        try {
            TossRefundResult result = paymentGateway.refund(
                payment.getPgTxId(), payment.getId(), payment.getTotalAmount()
            );
            payment.completeRefund(result.refundedAt());
            refund.complete(result.refundedAt());
            paymentRepository.save(payment);
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, code={}, reason={}",
                payment.getId(), e.getFailureCode(), e.getFailureReason());
            payment.restoreToRefundFailed();
            refund.fail();
            paymentRepository.save(payment);
            refundRepository.save(refund);
        }
    }
}
