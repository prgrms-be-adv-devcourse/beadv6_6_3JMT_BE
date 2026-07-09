package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import com.prompthub.common.event.EventMessage;
import com.prompthub.user.global.exception.SettlementEventDeserializeException;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedPayload;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventConsumer {

    private final ObjectMapper objectMapper;
    private final SeedSellerSettlementUseCase seedSellerSettlementUseCase;

    @KafkaListener(
            topics = "${user.kafka.consumer.settlement.topic}",
            groupId = "user-service",
            containerFactory = "settlementEventKafkaListenerContainerFactory",
            autoStartup = "${user.kafka.listener.settlement.enabled:false}"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        JsonNode root = readTree(message);
        String eventTypeStr = root.path("eventType").stringValue(null);
        SettlementEventType eventType = SettlementEventType.from(eventTypeStr);

        switch (eventType) {
            case SETTLEMENT_CREATED -> {
                EventMessage<SettlementCreatedPayload> eventMessage =
                        toEventMessage(root, SettlementCreatedPayload.class);
                seedSellerSettlementUseCase.seed(eventMessage.payload());
            }
            case UNKNOWN -> log.warn("지원하지 않는 정산 이벤트 타입입니다. eventType={}", eventTypeStr);
        }

        acknowledgment.acknowledge();
    }

    private JsonNode readTree(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (JacksonException exception) {
            throw new SettlementEventDeserializeException("정산 이벤트 메시지 파싱에 실패했습니다.", exception);
        }
    }

    private <T> EventMessage<T> toEventMessage(JsonNode root, Class<T> payloadType) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(EventMessage.class, payloadType);
            return objectMapper.treeToValue(root, type);
        } catch (JacksonException exception) {
            throw new SettlementEventDeserializeException("정산 이벤트 페이로드 역직렬화에 실패했습니다.", exception);
        }
    }
}
