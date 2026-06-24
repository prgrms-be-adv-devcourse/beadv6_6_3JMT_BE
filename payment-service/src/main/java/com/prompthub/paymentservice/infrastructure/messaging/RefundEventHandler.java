package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.TossRefundResult;
import com.prompthub.paymentservice.domain.event.PaymentRefundRequestedEvent;
import com.prompthub.paymentservice.domain.exception.InvalidRefundStateException;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefundEventHandler {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final KafkaPaymentEventPublisher kafkaPaymentEventPublisher;
    private final PlatformTransactionManager transactionManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundRequested(PaymentRefundRequestedEvent event) {
        // @Transactional(REQUIRES_NEW) AOP는 AFTER_COMMIT 콜백 내에서 actualNewSynchronization=false로
        // 설정되어 triggerAfterCommit()이 실행되지 않는다. TransactionTemplate으로 직접 제어.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        record RefundOutcome(Payment payment, Refund refund) {}

        RefundOutcome outcome = tx.execute(status -> {
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
                return new RefundOutcome(payment, refund);
            } catch (PaymentGatewayException e) {
                log.error("PG 환불 실패 — paymentId={}, code={}, reason={}",
                    payment.getId(), e.getFailureCode(), e.getFailureReason());
                payment.restoreToRefundFailed();
                refund.fail();
                paymentRepository.save(payment);
                refundRepository.save(refund);
                return null;
            } catch (InvalidRefundStateException e) {
                log.error("환불 상태 불변 위반 — paymentId={}, refundId={}, message={}",
                    payment.getId(), refund.getId(), e.getMessage());
                status.setRollbackOnly();
                return null;
            }
        });

        if (outcome != null) {
            kafkaPaymentEventPublisher.publishRefunded(outcome.payment(), outcome.refund());
        }
    }
}
