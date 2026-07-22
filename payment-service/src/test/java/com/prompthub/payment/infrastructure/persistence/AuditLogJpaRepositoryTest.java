package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
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
        assertThat(found.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(found.getEntityId()).isEqualTo(payment.getId());
        assertThat(found.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(found.getActorId()).isEqualTo(payment.getUserId());
        assertThat(found.getNewStatus()).isEqualTo("PAID");
        assertThat(found.getDetail()).isNull();
        assertThat(found.getOccurredAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
