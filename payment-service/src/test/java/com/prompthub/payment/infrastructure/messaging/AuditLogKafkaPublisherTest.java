package com.prompthub.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.payment.infrastructure.messaging.dto.AuditLogMessage;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AuditLogKafkaPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final AuditLogKafkaPublisher publisher = new AuditLogKafkaPublisher(kafkaTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void 감사로그를_payment_audit_log_토픽으로_발행한다() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        RecordMetadata recordMetadata = mock(RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(sendResult));

        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog auditLog = AuditLog.forPaymentApproved(payment);

        publisher.publish(auditLog);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(PaymentTopic.AUDIT_LOG), eq(auditLog.getId().toString()), captor.capture());

        AuditLogMessage message = (AuditLogMessage) captor.getValue();
        assertThat(message.id()).isEqualTo(auditLog.getId());
        assertThat(message.orderId()).isEqualTo(auditLog.getOrderId());
        assertThat(message.entityType()).isEqualTo("PAYMENT");
        assertThat(message.entityId()).isEqualTo(auditLog.getEntityId());
        assertThat(message.eventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(message.actorId()).isEqualTo(auditLog.getActorId());
        assertThat(message.newStatus()).isEqualTo("PAID");
        assertThat(message.failureCode()).isNull();
        assertThat(message.detail()).isNull();
        assertThat(message.occurredAt()).isEqualTo(auditLog.getOccurredAt());
    }
}
