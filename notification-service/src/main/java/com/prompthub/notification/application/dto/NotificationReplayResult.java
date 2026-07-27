package com.prompthub.notification.application.dto;
import java.util.List;
public record NotificationReplayResult(List<NotificationSseEvent> events, boolean syncRequired) { public static final int REPLAY_LIMIT = 100; }
