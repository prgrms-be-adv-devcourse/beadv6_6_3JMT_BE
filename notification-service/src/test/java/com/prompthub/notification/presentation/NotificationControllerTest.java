package com.prompthub.notification.presentation;

import com.prompthub.notification.application.usecase.NotificationUseCase;
import com.prompthub.notification.infra.realtime.SseConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationControllerTest {

    @Test
    void stream_delegatesToConnectionRegistry() {
        NotificationUseCase notificationUseCase = mock(NotificationUseCase.class);
        SseConnectionRegistry connectionRegistry = mock(SseConnectionRegistry.class);
        UUID recipientId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(connectionRegistry.register(org.mockito.ArgumentMatchers.eq(recipientId), org.mockito.ArgumentMatchers.any())).thenReturn(emitter);
        NotificationController controller = new NotificationController(notificationUseCase, connectionRegistry);

        assertThat(controller.stream(recipientId, null)).isSameAs(emitter);
    }
}
