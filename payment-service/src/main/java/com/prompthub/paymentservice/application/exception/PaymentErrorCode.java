package com.prompthub.paymentservice.application.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "PAY002", "이미 결제된 주문입니다."),
    PG_ERROR(HttpStatus.BAD_GATEWAY, "PAY003", "PG사 처리 중 오류가 발생했습니다."),
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "PAY_FAILED", "PG사 결제가 실패했습니다."),
    REFUND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "PAY004", "환불 가능한 상태가 아닙니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY005", "결제 건을 찾을 수 없습니다."),
    UNAUTHORIZED_REFUND(HttpStatus.FORBIDDEN, "PAY006", "본인 결제 건만 환불할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
