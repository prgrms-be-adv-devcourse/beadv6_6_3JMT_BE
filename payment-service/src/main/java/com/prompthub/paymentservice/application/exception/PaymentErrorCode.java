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
    PG_INVALID_REQUEST(HttpStatus.BAD_GATEWAY, "PAY003", "잘못된 API 요청으로 인한 PG사 오류입니다."),
    PG_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "PAY_PG_5XX", "PG사 서버 오류가 발생했습니다."),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PAY_FAILED", "PG사 결제가 실패했습니다."),
    REFUND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "PAY004", "환불 가능한 상태가 아닙니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY005", "결제 건을 찾을 수 없습니다."),
    UNAUTHORIZED_REFUND(HttpStatus.FORBIDDEN, "PAY006", "본인 결제 건만 환불할 수 있습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY008", "주문 정보를 찾을 수 없습니다."),
    ORDER_INFO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY009", "주문 정보를 확보할 수 없습니다."),
    NOT_ORDER_OWNER(HttpStatus.FORBIDDEN, "PAY010", "본인 주문만 결제할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
