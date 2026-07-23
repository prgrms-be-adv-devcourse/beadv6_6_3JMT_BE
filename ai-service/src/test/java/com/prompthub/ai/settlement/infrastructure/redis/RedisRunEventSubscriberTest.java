package com.prompthub.ai.settlement.infrastructure.redis;

import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.application.service.RunFutureRegistry;
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

class RedisRunEventSubscriberTest {

    @Test
    void cancelledEventClosesEmittersBeforeCancellingKnownLocalFuture() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SseEmitterRegistry emitterRegistry = mock(SseEmitterRegistry.class);
        RunFutureRegistry futureRegistry = mock(RunFutureRegistry.class);
        RedisRunEventSubscriber subscriber = new RedisRunEventSubscriber(
                objectMapper,
                emitterRegistry,
                futureRegistry
        );
        RunEvent event = RunEvent.cancelled(
                UUID.randomUUID(),
                Instant.parse("2026-07-22T12:00:00Z")
        );
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        subscriber.onMessage(message, new byte[0]);

        InOrder order = inOrder(emitterRegistry, futureRegistry);
        order.verify(emitterRegistry).dispatch(event);
        order.verify(futureRegistry).cancel(event.runId());
    }
}
