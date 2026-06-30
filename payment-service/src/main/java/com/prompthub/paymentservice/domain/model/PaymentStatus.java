package com.prompthub.paymentservice.domain.model;

public enum PaymentStatus {
    READY,
    REQUESTED,
    PAID,
    FAILED,
    REFUNDING,
    REFUNDED,
    UNKNOWN
}
