package com.prompthub.notification.infra.sse;

import com.prompthub.notification.application.service.NotificationItem;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import com.prompthub.notification.global.exception.NotificationException;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseConnectionRegistry {
    private final SseProperties properties;
    private final ConcurrentMap<UUID, Set<SseEmitter>> emittersByRecipient = new ConcurrentHashMap<>();

    public SseConnectionRegistry(SseProperties properties) {
        this.properties = properties;
    }

    public SseEmitter connect(UUID recipientId) {
        Set<SseEmitter> emitters = emittersByRecipient.computeIfAbsent(recipientId, ignored -> ConcurrentHashMap.newKeySet());
        SseEmitter emitter = new SseEmitter(properties.timeoutMillis());
        synchronized (emitters) {
            if (emitters.size() >= properties.maxConnectionsPerUser()) {
                throw new NotificationException(NotificationErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
            }
            emitters.add(emitter);
        }
        emitter.onCompletion(() -> remove(recipientId, emitter));
        emitter.onTimeout(() -> remove(recipientId, emitter));
        emitter.onError(ignored -> remove(recipientId, emitter));
        return emitter;
    }

    public void publish(UUID recipientId, NotificationItem notification) {
        emittersByRecipient.getOrDefault(recipientId, Set.of())
            .forEach(emitter -> send(recipientId, emitter, notification));
    }

    public void send(UUID recipientId, SseEmitter emitter, NotificationItem notification) {
        try {
            emitter.send(SseEmitter.event()
                .id(Long.toString(notification.sequence()))
                .name("notification")
                .data(notification));
        } catch (IOException exception) {
            remove(recipientId, emitter);
        }
    }

    public int connectionCount(UUID recipientId) {
        return emittersByRecipient.getOrDefault(recipientId, Set.of()).size();
    }

    @Scheduled(fixedDelayString = "${notification.sse.heartbeat-interval-millis}")
    public void sendHeartbeat() {
        emittersByRecipient.forEach((recipientId, emitters) ->
            emitters.forEach(emitter -> sendHeartbeat(recipientId, emitter))
        );
    }

    private void sendHeartbeat(UUID recipientId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
        } catch (IOException exception) {
            remove(recipientId, emitter);
        }
    }

    private void remove(UUID recipientId, SseEmitter emitter) {
        emittersByRecipient.computeIfPresent(recipientId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
