package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogEventListenerTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AuditLogEventListener listener = new AuditLogEventListener(auditLogRepository);

    @Test
    void 결제_승인_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentApproved(new PaymentApprovedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("PAID");
        assertThat(auditLog.getDetail()).isNull();
    }

    @Test
    void 결제_실패_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.fail("REJECT", "카드 거절", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentFailed(new PaymentFailedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getDetail()).isEqualTo("카드 거절");
    }

    @Test
    void 환불_완료_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.complete(OffsetDateTime.now());

        listener.onPaymentRefunded(new PaymentRefundedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(auditLog.getEntityId()).isEqualTo(refund.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REFUNDED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("COMPLETED");
        assertThat(auditLog.getDetail()).isNull();
    }

    @Test
    void 환불_실패_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-4", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.fail("PG 오류");

        listener.onPaymentRefundFailed(new PaymentRefundFailedEvent(payment, refund, "PG 오류"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REFUND_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getDetail()).isEqualTo("PG 오류");
    }

    @Test
    void 감사로그_저장_실패해도_예외를_전파하지_않는다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-5", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> listener.onPaymentApproved(new PaymentApprovedEvent(payment)));
    }
}
