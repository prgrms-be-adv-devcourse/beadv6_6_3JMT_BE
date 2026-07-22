package com.prompthub.ai.settlement.infrastructure.redis;

import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.application.service.RunFutureRegistry;
import com.prompthub.ai.settlement.presentation.SseEmitterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class RedisRunEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SseEmitterRegistry emitterRegistry;
    private final RunFutureRegistry futureRegistry;

    public RedisRunEventSubscriber(
            ObjectMapper objectMapper,
            SseEmitterRegistry emitterRegistry,
            RunFutureRegistry futureRegistry
    ) {
        this.objectMapper = objectMapper;
        this.emitterRegistry = emitterRegistry;
        this.futureRegistry = futureRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RunEvent event = objectMapper.readValue(message.getBody(), RunEvent.class);
            emitterRegistry.dispatch(event);
            if (event.type() == RunEvent.RunEventType.CANCELLED) {
                futureRegistry.cancel(event.runId());
            }
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("AI run Pub/Sub event 역직렬화 실패 - type={}", exception.getClass().getSimpleName());
        }
    }
}
