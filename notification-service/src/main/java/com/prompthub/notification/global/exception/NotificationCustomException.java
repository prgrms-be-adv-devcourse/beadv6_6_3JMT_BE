package com.prompthub.notification.global.exception;

import com.prompthub.exception.BusinessException;

public class NotificationCustomException extends BusinessException {
    public NotificationCustomException(NotificationErrorCode errorCode) {
        super(errorCode);
    }
}
