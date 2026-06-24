package com.prompthub.paymentservice.infrastructure.scheduling;

import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.TossRefundResult;
import com.prompthub.paymentservice.application.gateway.persistence.PaymentRepository;
import com.prompthub.paymentservice.application.gateway.persistence.RefundRepository;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.KafkaPaymentEventPublisher;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundRetryScheduler {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final KafkaPaymentEventPublisher kafkaPaymentEventPublisher;

    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void retryStaleRefunding() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(30);
        List<Payment> stalePayments = paymentRepository
            .findByStatusAndUpdatedAtBefore(PaymentStatus.REFUNDING, threshold);

        for (Payment payment : stalePayments) {
            Refund refund = refundRepository.findByPaymentId(payment.getId()).orElse(null);
            if (refund == null) {
                continue;
            }

            try {
                TossRefundResult result = paymentGateway.refund(
                    payment.getPgTxId(), payment.getId(), payment.getTotalAmount()
                );
                payment.completeRefund(result.refundedAt());
                refund.complete(result.refundedAt());
                paymentRepository.save(payment);
                refundRepository.save(refund);
                final Payment committedPayment = payment;
                final Refund committedRefund = refund;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        kafkaPaymentEventPublisher.publishRefunded(committedPayment, committedRefund);
                    }
                });
            } catch (PaymentGatewayException e) {
                log.error("스케줄러 환불 재시도 실패 — paymentId={}, code={}, reason={}",
                    payment.getId(), e.getFailureCode(), e.getFailureReason());
                payment.restoreToRefundFailed();
                refund.fail();
                paymentRepository.save(payment);
                refundRepository.save(refund);
            }
        }
    }
}
