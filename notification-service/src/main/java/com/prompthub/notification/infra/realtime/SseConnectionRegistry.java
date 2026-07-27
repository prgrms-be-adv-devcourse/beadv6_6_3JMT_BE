package com.prompthub.notification.infra.realtime;

import com.prompthub.notification.application.dto.NotificationReplayResult;
import com.prompthub.notification.application.dto.NotificationSyncRequired;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import com.prompthub.notification.global.exception.NotificationCustomException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class SseConnectionRegistry {
    private static final int MAX_CONNECTIONS_PER_RECIPIENT = 3;
    private final Map<UUID, Map<String, SseConnectionSession>> sessions = new ConcurrentHashMap<>();

    public SseEmitter register(UUID recipientId) { return register(recipientId, () -> new NotificationReplayResult(java.util.List.of(), false)); }
    public SseEmitter register(UUID recipientId, Supplier<NotificationReplayResult> supplier) {
        String id = UUID.randomUUID().toString(); SseConnectionSession session = new SseConnectionSession();
        sessions.compute(recipientId, (key, current) -> { Map<String,SseConnectionSession> value = current == null ? new ConcurrentHashMap<>() : current;
            if (value.size() >= MAX_CONNECTIONS_PER_RECIPIENT) throw new NotificationCustomException(NotificationErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED); value.put(id, session); return value; });
        session.emitter.onCompletion(() -> remove(recipientId,id)); session.emitter.onTimeout(() -> remove(recipientId,id)); session.emitter.onError(e -> remove(recipientId,id));
        try { NotificationReplayResult replay = supplier.get(); if (replay.syncRequired()) session.sendSyncRequired(NotificationSyncRequired.replayLimitExceeded()); else { Map<UUID,Object> values = new LinkedHashMap<>(); replay.events().forEach(e -> values.put(e.notificationId(), e.payload())); session.completeReplay(values); } return session.emitter;
        } catch (RuntimeException | IOException e) { remove(recipientId,id); session.emitter.completeWithError(e); throw e instanceof RuntimeException r ? r : new RuntimeException(e); }
    }
    public void send(UUID recipientId, UUID notificationId, Object payload) { sessions.getOrDefault(recipientId, Map.of()).forEach((id, session) -> { try { session.sendLive(notificationId,payload); } catch (IOException e) { remove(recipientId,id); } }); }
    private void remove(UUID recipientId,String id) { Map<String,SseConnectionSession> value=sessions.get(recipientId); if(value!=null){value.remove(id);if(value.isEmpty()) sessions.remove(recipientId,value);} }
}
