package com.prompthub.ai.settlement.presentation.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRegistryTest {

    @Test
    void heartbeatRemovesOnlyTheBrokenConnection() {
        UUID runId = UUID.randomUUID();
        SseEmitterRegistry registry = new SseEmitterRegistry();
        CountingEmitter healthy = new CountingEmitter(false);
        CountingEmitter broken = new CountingEmitter(true);
        registry.register(runId, healthy, true);
        registry.register(runId, broken, true);

        registry.heartbeat();

        assertThat(healthy.sentCount()).isEqualTo(1);
        assertThat(registry.connectionCount(runId)).isEqualTo(1);
    }

    private static final class CountingEmitter extends SseEmitter {

        private final boolean fail;
        private final AtomicInteger sentCount = new AtomicInteger();

        private CountingEmitter(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (fail) {
                throw new IOException("connection closed");
            }
            sentCount.incrementAndGet();
        }

        private int sentCount() {
            return sentCount.get();
        }
    }
}
