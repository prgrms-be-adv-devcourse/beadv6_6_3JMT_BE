package com.prompthub.notification.application.dto;
public record NotificationSyncRequired(String code, int replayLimit) { public static NotificationSyncRequired replayLimitExceeded() { return new NotificationSyncRequired("REPLAY_LIMIT_EXCEEDED", 100); } }
