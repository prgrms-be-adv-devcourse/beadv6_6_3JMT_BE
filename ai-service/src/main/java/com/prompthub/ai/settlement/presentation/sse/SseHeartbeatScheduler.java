package com.prompthub.ai.settlement.presentation.sse;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SseHeartbeatScheduler {

    private final SseEmitterRegistry emitterRegistry;

    public SseHeartbeatScheduler(SseEmitterRegistry emitterRegistry) {
        this.emitterRegistry = emitterRegistry;
    }

    @Scheduled(fixedDelayString = "${ai.sse.heartbeat:15s}")
    public void heartbeat() {
        emitterRegistry.heartbeat();
    }
}
