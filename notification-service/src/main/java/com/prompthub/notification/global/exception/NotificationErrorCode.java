package com.prompthub.notification.global.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N001", "알림을 찾을 수 없습니다."),
    SSE_CONNECTION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "N002", "알림 스트림 연결 한도를 초과했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
