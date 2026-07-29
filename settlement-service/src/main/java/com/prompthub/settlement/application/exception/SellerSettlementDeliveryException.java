package com.prompthub.settlement.application.exception;

import io.grpc.Status;
import lombok.Getter;

@Getter
public class SellerSettlementDeliveryException extends RuntimeException {

    private final Status.Code statusCode;
    private final boolean retryable;

    private SellerSettlementDeliveryException(
            Status.Code statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = statusCode == Status.Code.UNAVAILABLE
                || statusCode == Status.Code.DEADLINE_EXCEEDED;
    }

    public static SellerSettlementDeliveryException from(
            Status.Code statusCode, String message) {
        return new SellerSettlementDeliveryException(statusCode, message, null);
    }

    public static SellerSettlementDeliveryException from(
            Status.Code statusCode, String message, Throwable cause) {
        return new SellerSettlementDeliveryException(statusCode, message, cause);
    }
}
