package com.prompthub.payment.infrastructure.messaging;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.payment.infrastructure.messaging.dto.AuditLogMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(AuditLog auditLog) {
        AuditLogMessage message = new AuditLogMessage(
            auditLog.getId(), auditLog.getOrderId(), auditLog.getEntityType().name(),
            auditLog.getEntityId(), auditLog.getEventType().name(), auditLog.getActorId(),
            auditLog.getNewStatus(), auditLog.getFailureCode(), auditLog.getDetail(),
            auditLog.getOccurredAt(), auditLog.getCreatedAt()
        );
        kafkaTemplate.send(PaymentTopic.AUDIT_LOG, auditLog.getId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("감사로그 Kafka 발행 실패 — auditLogId={}, cause={}", auditLog.getId(), ex.getMessage());
                } else {
                    log.info("감사로그 Kafka 발행 성공 — auditLogId={}, partition={}, offset={}",
                        auditLog.getId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
    }
}
