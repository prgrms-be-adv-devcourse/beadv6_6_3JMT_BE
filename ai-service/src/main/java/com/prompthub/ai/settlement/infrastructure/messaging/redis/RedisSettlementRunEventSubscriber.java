package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import com.prompthub.ai.settlement.application.usecase.SettlementRunUseCase;
import com.prompthub.ai.settlement.domain.event.RunEvent;
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
    private final SettlementRunUseCase runUseCase;

    public RedisSettlementRunEventSubscriber(
            ObjectMapper objectMapper,
            SettlementRunUseCase runUseCase
    ) {
        this.objectMapper = objectMapper;
        this.runUseCase = runUseCase;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RunEvent event = objectMapper.readValue(message.getBody(), RunEvent.class);
            runUseCase.handleEvent(event);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("AI run Pub/Sub event 역직렬화 실패 - type={}", exception.getClass().getSimpleName());
        }
    }
}
