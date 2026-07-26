package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.application.service.run.SettlementRunTaskRegistry;
import com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.Message;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSettlementRunEventSubscriberTest {

    @Test
    void cancelledEventClosesEmittersBeforeCancellingKnownLocalFuture() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SseEmitterRegistry emitterRegistry = mock(SseEmitterRegistry.class);
        SettlementRunTaskRegistry taskRegistry = mock(SettlementRunTaskRegistry.class);
        RedisSettlementRunEventSubscriber subscriber = new RedisSettlementRunEventSubscriber(
                objectMapper,
                emitterRegistry,
                taskRegistry
        );
        RunEvent event = RunEvent.cancelled(
                UUID.randomUUID(),
                Instant.parse("2026-07-22T12:00:00Z")
        );
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        subscriber.onMessage(message, new byte[0]);

        InOrder order = inOrder(emitterRegistry, taskRegistry);
        order.verify(emitterRegistry).dispatch(event);
        order.verify(taskRegistry).cancel(event.runId());
    }
}
