package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import com.prompthub.common.event.EventMessage;
import com.prompthub.user.global.exception.SettlementEventContractViolationException;
import com.prompthub.user.global.exception.SettlementEventDeserializeException;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV1;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
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
        String eventTypeStr = resolveEventType(root);
        SettlementEventType eventType = SettlementEventType.from(eventTypeStr);

        switch (eventType) {
            case SETTLEMENT_CREATED -> consumeSettlementCreated(root);
            case UNKNOWN -> log.warn("지원하지 않는 정산 이벤트 타입입니다. eventType={}", eventTypeStr);
        }

        acknowledgment.acknowledge();
    }

    private String resolveEventType(JsonNode root) {
        JsonNode eventTypeNode = root.path("eventType");
        if (!eventTypeNode.isString() || eventTypeNode.stringValue().isBlank()) {
            throw deserializeException("정산 eventType 형식이 올바르지 않습니다.");
        }
        return eventTypeNode.stringValue();
    }

    private void consumeSettlementCreated(JsonNode root) {
        switch (resolvePayloadVersion(root)) {
            case 1 -> seedSellerSettlementUseCase.seed(
                    toEventMessage(root, SettlementCreatedEventV1.class).payload());
            case 2 -> consumeSettlementCreatedV2(
                    toEventMessage(root, SettlementCreatedEventV2.class).payload());
            default -> throw deserializeException("지원하지 않는 정산 payload version입니다.");
        }
    }

    private void consumeSettlementCreatedV2(SettlementCreatedEventV2 event) {
        try {
            event.validateContract();
        } catch (IllegalArgumentException exception) {
            throw new SettlementEventContractViolationException(
                    "정산 V2 이벤트 계약 검증에 실패했습니다: " + exception.getMessage(),
                    exception
            );
        }
        seedSellerSettlementUseCase.seed(event);
    }

    private int resolvePayloadVersion(JsonNode root) {
        JsonNode versionNode = root.path("payload").path("payloadVersion");
        if (versionNode.isMissingNode() || versionNode.isNull()) {
            return 1;
        }
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw deserializeException("정산 payload version 형식이 올바르지 않습니다.");
        }
        return versionNode.intValue();
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

    private SettlementEventDeserializeException deserializeException(String message) {
        return new SettlementEventDeserializeException(message, new IllegalArgumentException(message));
    }
}
