package com.prompthub.notification.global.exception;

import com.prompthub.exception.BusinessException;

public class NotificationException extends BusinessException {
    public NotificationException(NotificationErrorCode errorCode) { super(errorCode); }
}
