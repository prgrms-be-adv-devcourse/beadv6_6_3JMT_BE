package com.prompthub.notification.infra.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class SseConnectionSession {
    final SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
    private final Map<UUID, Object> buffered = new LinkedHashMap<>();
    private boolean replaying = true;

    synchronized void sendLive(UUID id, Object payload) throws IOException {
        if (replaying) buffered.putIfAbsent(id, payload);
        else send(id, payload);
    }
    synchronized void completeReplay(Map<UUID, Object> replay) throws IOException {
        for (var entry : replay.entrySet()) send(entry.getKey(), entry.getValue());
        for (var entry : buffered.entrySet()) if (!replay.containsKey(entry.getKey())) send(entry.getKey(), entry.getValue());
        buffered.clear(); replaying = false;
    }
    synchronized void sendSyncRequired(Object payload) throws IOException {
        emitter.send(SseEmitter.event().name("sync-required").data(payload));
        for (var entry : buffered.entrySet()) send(entry.getKey(), entry.getValue());
        buffered.clear(); replaying = false;
    }
    private void send(UUID id, Object payload) throws IOException { emitter.send(SseEmitter.event().name("notification").id(id.toString()).data(payload)); }
}
