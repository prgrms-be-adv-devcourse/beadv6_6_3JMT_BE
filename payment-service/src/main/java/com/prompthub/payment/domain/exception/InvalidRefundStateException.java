package com.prompthub.payment.domain.exception;

public class InvalidRefundStateException extends RuntimeException {

    public InvalidRefundStateException(String message) {
        super(message);
    }
}
