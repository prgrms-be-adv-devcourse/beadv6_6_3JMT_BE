package com.prompthub.notification.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.UUID;
import java.time.Instant;
import com.prompthub.notification.application.service.CreateNotificationCommand;
import com.prompthub.notification.application.service.NotificationCommandService;
import com.prompthub.notification.application.service.StoredNotification;
import com.prompthub.notification.domain.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired NotificationCommandService commandService;
    @Test
    void listsNotificationsForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), userId, NotificationType.ORDER_PAID, "결제 완료", "결제가 완료되었습니다.",
            "ORDER", orderId, "notification-service", Instant.now()
        ));

        mockMvc.perform(get("/api/v2/notifications").header("X-User-Id", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].type").value("ORDER_PAID"))
            .andExpect(jsonPath("$.data[0].referenceType").value("ORDER"))
            .andExpect(jsonPath("$.data[0].referenceId").value(orderId.toString()));
    }

    @Test
    void returnsUnreadCountForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v2/notifications/unread-count").header("X-User-Id", UUID.randomUUID()))
            .andExpect(status().isOk());
    }

    @Test
    void marksOwnedNotificationAsRead() throws Exception {
        UUID userId = UUID.randomUUID();
        StoredNotification notification = commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), userId, NotificationType.ORDER_PAID, "결제 완료", "결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
        ));
        mockMvc.perform(patch("/api/v2/notifications/{id}/read", notification.id()).header("X-User-Id", userId))
            .andExpect(status().isOk());
    }

    @Test
    void returnsNotFoundCodeWhenNotificationDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/v2/notifications/{id}/read", UUID.randomUUID())
                .header("X-User-Id", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("N001"));
    }

    @Test
    void returnsAccessDeniedCodeWhenNotificationBelongsToAnotherUser() throws Exception {
        StoredNotification notification = commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), UUID.randomUUID(), NotificationType.ORDER_PAID, "결제 완료", "결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
        ));

        mockMvc.perform(patch("/api/v2/notifications/{id}/read", notification.id())
                .header("X-User-Id", UUID.randomUUID()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("N002"));
    }

    @Test
    void marksAllOwnedUnreadNotificationsAsRead() throws Exception {
        UUID userId = UUID.randomUUID();
        commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), userId, NotificationType.ORDER_PAID, "결제 완료", "결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
        ));

        mockMvc.perform(patch("/api/v2/notifications/read").header("X-User-Id", userId))
            .andExpect(status().isOk());
    }
}
