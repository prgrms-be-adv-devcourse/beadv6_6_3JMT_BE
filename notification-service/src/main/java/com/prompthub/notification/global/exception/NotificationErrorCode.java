package com.prompthub.notification.global.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N001", "알림을 찾을 수 없습니다."),
    NOTIFICATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "N002", "해당 알림에 접근할 수 없습니다."),
    SSE_CONNECTION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "N003", "알림 연결 한도를 초과했습니다.");
    private final HttpStatus status;
    private final String code;
    private final String message;
}
