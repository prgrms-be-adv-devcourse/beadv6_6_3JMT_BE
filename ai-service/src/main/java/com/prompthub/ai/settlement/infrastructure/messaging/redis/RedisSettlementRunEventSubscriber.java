package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.application.service.run.SettlementRunTaskRegistry;
import com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class RedisSettlementRunEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SseEmitterRegistry emitterRegistry;
    private final SettlementRunTaskRegistry taskRegistry;

    public RedisSettlementRunEventSubscriber(
            ObjectMapper objectMapper,
            SseEmitterRegistry emitterRegistry,
            SettlementRunTaskRegistry taskRegistry
    ) {
        this.objectMapper = objectMapper;
        this.emitterRegistry = emitterRegistry;
        this.taskRegistry = taskRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RunEvent event = objectMapper.readValue(message.getBody(), RunEvent.class);
            emitterRegistry.dispatch(event);
            if (event.type() == RunEvent.RunEventType.CANCELLED) {
                taskRegistry.cancel(event.runId());
            }
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("AI run Pub/Sub event 역직렬화 실패 - type={}", exception.getClass().getSimpleName());
        }
    }
}
