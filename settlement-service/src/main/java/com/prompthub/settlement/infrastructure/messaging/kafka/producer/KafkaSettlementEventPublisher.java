package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import com.prompthub.settlement.application.event.SettlementEventType;
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

    private static final String AGGREGATE_TYPE = "SETTLEMENT";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public KafkaSettlementEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${settlement.kafka.producer.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publishSettlementCreated(SettlementCreatedPayload payload) {
        UUID settlementId = payload.settlementId();
        EventMessage<SettlementCreatedPayload> message = new EventMessage<>(
                UUID.randomUUID(),
                SettlementEventType.SETTLEMENT_CREATED.code(),
                LocalDateTime.now(),
                AGGREGATE_TYPE,
                settlementId,
                payload);
        try {
            kafkaTemplate.send(topic, settlementId.toString(), message)
                    // 커밋 이후의 비동기 전송 실패(broker down 등)는 at-most-once 로 로깅만 한다.
                    // (배치 스텝은 성공 — 재전송은 후속 아웃박스(#261)가 담당)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("정산 생성 이벤트 발행 실패(async). settlementId={}", settlementId, ex);
                        }
                    });
        } catch (Exception e) {
            // 동기 단계 실패(직렬화·설정 오류 등)는 코드/설정 버그일 가능성이 높아 예외로 던져 조기에 드러낸다.
            log.error("정산 생성 이벤트 발행 실패. settlementId={}", settlementId, e);
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED, e);
        }
    }
}
