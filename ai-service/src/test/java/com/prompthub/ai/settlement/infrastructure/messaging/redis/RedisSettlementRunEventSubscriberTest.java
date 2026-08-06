package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import com.prompthub.ai.settlement.application.usecase.SettlementRunUseCase;
import com.prompthub.ai.settlement.domain.event.RunEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisSettlementRunEventSubscriberTest {

    @Test
    void delegatesDeserializedEventToRunUseCase() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SettlementRunUseCase useCase = mock(SettlementRunUseCase.class);
        RedisSettlementRunEventSubscriber subscriber =
                new RedisSettlementRunEventSubscriber(objectMapper, useCase);
        RunEvent event = RunEvent.cancelled(
                UUID.randomUUID(),
                Instant.parse("2026-07-22T12:00:00Z"));
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        subscriber.onMessage(message, new byte[0]);

        verify(useCase).handleEvent(event);
    }

    @Test
    void ignoresMalformedPayloadWithoutCallingRunUseCase() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SettlementRunUseCase useCase = mock(SettlementRunUseCase.class);
        RedisSettlementRunEventSubscriber subscriber =
                new RedisSettlementRunEventSubscriber(objectMapper, useCase);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(message, new byte[0]);

        verifyNoInteractions(useCase);
    }
}
