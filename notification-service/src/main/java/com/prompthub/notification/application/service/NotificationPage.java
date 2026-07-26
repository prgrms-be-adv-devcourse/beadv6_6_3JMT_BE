package com.prompthub.notification.application.service;

import java.util.List;

public record NotificationPage(List<NotificationItem> items, long total, boolean hasNext) {
}
