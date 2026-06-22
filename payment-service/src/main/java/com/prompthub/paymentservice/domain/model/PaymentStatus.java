package com.prompthub.paymentservice.domain.model;

public enum PaymentStatus {
    READY,
    REQUESTED,
    PAID,
    FAILED,
    CANCELING,
    CANCELED,
    CANCEL_FAILED,
    REFUNDING,
    REFUNDED,
    UNKNOWN
}
