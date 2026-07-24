package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogEventListener {

    private final AuditLogRepository auditLogRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        save(AuditLog.forPaymentApproved(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        save(AuditLog.forPaymentFailed(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundCompleted(event.payment(), event.refund()), event.refund().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundFailed(event.payment(), event.refund()), event.refund().getId());
    }

    private void save(AuditLog auditLog, UUID entityId) {
        try {
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("감사로그 저장 실패 — entityId={}, eventType={}, cause={}",
                entityId, auditLog.getEventType(), e.getMessage());
        }
    }
}
