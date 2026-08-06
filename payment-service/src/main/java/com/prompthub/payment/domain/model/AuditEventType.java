package com.prompthub.payment.domain.model;

public enum AuditEventType {
    PAYMENT_REQUESTED,
    PAYMENT_APPROVED,
    PAYMENT_FAILED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    REFUND_FAILED
}
