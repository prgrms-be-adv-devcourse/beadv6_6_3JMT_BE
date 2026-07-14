package com.prompthub.paymentservice.domain.model;

public enum PaymentStatus {
    READY,
    REQUESTED,
    PAID,
    FAILED,
    PARTIAL_REFUNDED,
    ALL_REFUNDED,
    UNKNOWN
}
