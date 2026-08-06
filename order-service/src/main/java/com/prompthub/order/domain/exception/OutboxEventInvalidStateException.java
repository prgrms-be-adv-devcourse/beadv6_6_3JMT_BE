package com.prompthub.order.domain.exception;

import com.prompthub.order.domain.enums.OutboxEventStatus;

public class OutboxEventInvalidStateException extends RuntimeException {

    public OutboxEventInvalidStateException(OutboxEventStatus status, String action) {
        super("Cannot %s outbox event in %s state".formatted(action, status));
    }
}
