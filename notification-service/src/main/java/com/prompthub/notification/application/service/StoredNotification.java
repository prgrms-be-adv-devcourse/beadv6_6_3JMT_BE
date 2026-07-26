package com.prompthub.notification.application.service;

import java.util.UUID;

public record StoredNotification(UUID id, long sequence, boolean created) {
}
