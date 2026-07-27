package com.prompthub.notification.infra.messaging.kafka;

public class NotificationEventContractException extends RuntimeException {

    public NotificationEventContractException(String message) {
        super(message);
    }
}
