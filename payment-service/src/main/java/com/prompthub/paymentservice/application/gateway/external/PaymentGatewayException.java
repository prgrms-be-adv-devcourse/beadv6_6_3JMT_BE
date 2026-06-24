package com.prompthub.paymentservice.application.gateway.external;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayException extends RuntimeException {

    private final PaymentErrorCode errorCode;
    private final String failureCode;
    private final String failureReason;
    private final String requestPayload;
    private final String responsePayload;

    public PaymentGatewayException(PaymentErrorCode errorCode, String failureCode, String failureReason,
                                   String requestPayload, String responsePayload) {
        super(failureReason);
        this.errorCode = errorCode;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
    }
}
