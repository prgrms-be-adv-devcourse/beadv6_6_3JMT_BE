package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.support.AbstractJpaTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    AuditLogJpaRepository auditLogJpaRepository;

    @Test
    void 감사로그_저장_조회_라운드트립() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-audit", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());

        AuditLog auditLog = AuditLog.forPaymentApproved(payment);
        AuditLog saved = auditLogJpaRepository.saveAndFlush(auditLog);

        AuditLog found = auditLogJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("AuditLog not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(found.getEntityId()).isEqualTo(payment.getId());
        assertThat(found.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(found.getActorId()).isEqualTo(payment.getUserId());
        assertThat(found.getNewStatus()).isEqualTo("PAID");
        assertThat(found.getFailureCode()).isNull();
        assertThat(found.getDetail()).isNull();
        assertThat(found.getOccurredAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 환불_실패_감사로그는_failure_code와_detail을_함께_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-audit-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, null);
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());

        AuditLog auditLog = AuditLog.forRefundFailed(payment, refund);
        AuditLog saved = auditLogJpaRepository.saveAndFlush(auditLog);

        AuditLog found = auditLogJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("AuditLog not found"));

        assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.REFUND);
        assertThat(found.getEventType()).isEqualTo(AuditEventType.REFUND_FAILED);
        assertThat(found.getFailureCode()).isEqualTo("CANCEL_FAILED");
        assertThat(found.getDetail()).isEqualTo("PG 오류");
    }
}
