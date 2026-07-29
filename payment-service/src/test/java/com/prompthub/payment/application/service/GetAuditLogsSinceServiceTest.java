package com.prompthub.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetAuditLogsSinceServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final GetAuditLogsSinceService service = new GetAuditLogsSinceService(auditLogRepository);

    @Test
    void since_이후_감사로그를_결과로_변환하고_2000건_상한을_적용한다() {
        Payment payment = Payment.create(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog auditLog = AuditLog.forPaymentApproved(payment);
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(15);
        when(auditLogRepository.findAllCreatedAfter(eq(since), anyInt())).thenReturn(List.of(auditLog));

        List<AuditLogResult> results = service.getSince(since);

        verify(auditLogRepository).findAllCreatedAfter(eq(since), eq(2000));
        assertThat(results).hasSize(1);
        AuditLogResult result = results.get(0);
        assertThat(result.id()).isEqualTo(auditLog.getId());
        assertThat(result.orderId()).isEqualTo(auditLog.getOrderId());
        assertThat(result.entityType()).isEqualTo("PAYMENT");
        assertThat(result.eventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(result.newStatus()).isEqualTo("PAID");
        assertThat(result.occurredAt()).isEqualTo(auditLog.getOccurredAt());
        assertThat(result.createdAt()).isEqualTo(auditLog.getCreatedAt());
    }
}
