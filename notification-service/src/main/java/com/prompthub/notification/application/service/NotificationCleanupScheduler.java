package com.prompthub.notification.application.service;

import com.prompthub.notification.application.usecase.NotificationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    private final NotificationUseCase notificationUseCase;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void deleteExpiredNotifications() {
        notificationUseCase.deleteExpiredNotifications();
    }
}
