package com.prompthub.order.global.exception;

public class EventPayloadMappingException extends RuntimeException {
    public EventPayloadMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
