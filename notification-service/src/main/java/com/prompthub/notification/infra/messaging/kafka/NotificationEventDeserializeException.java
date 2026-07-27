package com.prompthub.notification.infra.messaging.kafka;

public class NotificationEventDeserializeException extends RuntimeException {

    public NotificationEventDeserializeException(Throwable cause) {
        super("Kafka event JSON could not be deserialized", cause);
    }
}
