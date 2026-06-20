package com.prompthub.paymentservice.domain;

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
