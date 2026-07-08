package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import com.prompthub.settlement.application.event.SettlementCreatedMessage;
import com.prompthub.settlement.application.event.SettlementEventEnvelope;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KafkaSettlementEventPublisher implements SettlementEventPublisher {

    private static final String EVENT_TYPE = "settlement.created";
    private static final int EVENT_VERSION = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public KafkaSettlementEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${settlement.kafka.producer.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publishSettlementCreated(SettlementCreatedMessage message) {
        SettlementEventEnvelope<SettlementCreatedMessage> envelope = new SettlementEventEnvelope<>(
                UUID.randomUUID(), EVENT_TYPE, EVENT_VERSION, LocalDateTime.now(),
                message.settlementId(), message);
        try {
            kafkaTemplate.send(topic, message.settlementId().toString(), envelope);
        } catch (Exception e) {
            log.error("정산 생성 이벤트 발행 실패. settlementId={}", message.settlementId(), e);
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED, e);
        }
    }
}
