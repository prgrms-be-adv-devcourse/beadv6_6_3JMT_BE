package com.prompthub.order.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode implements com.prompthub.exception.ErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "V001", "입력값 검증 실패"),
    INVALID_AUTHENTICATION(HttpStatus.UNAUTHORIZED, "A003", "토큰이 만료되었거나 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "A004", "권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS001", "서버 내부 오류가 발생했습니다."),
    PRODUCT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SYS002", "상품 서비스를 사용할 수 없습니다."),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "O001", "주문을 찾을 수 없습니다."),
    ORDER_CANCEL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "O002", "취소할 수 없는 주문 상태입니다."),
    PRODUCT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "O003", "판매 중이 아닌 상품입니다."),
    CART_EMPTY(HttpStatus.BAD_REQUEST, "O004", "장바구니가 비어 있습니다."),
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "O005", "장바구니를 찾을 수 없습니다."),
    CART_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "O006", "장바구니 상품을 찾을 수 없습니다."),
    CART_PRODUCT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "O007", "해당 장바구니 상품에 접근할 수 없습니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "O008", "해당 주문에 접근할 수 없습니다."),
    INVALID_ORDER_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "O009", "허용되지 않는 주문 상태 변경입니다."),
    ORDER_ALREADY_PROCESSED(HttpStatus.CONFLICT, "O010", "이미 처리된 주문입니다."),
    ORDER_PRICE_CHANGED(HttpStatus.CONFLICT, "O011", "상품 가격이 변경되었습니다."),
    ORDER_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "O012", "주문 상품을 찾을 수 없습니다."),
    ORDER_PAYMENT_STATUS_INVALID(HttpStatus.BAD_REQUEST, "O013", "결제 완료 처리할 수 없는 주문 상태입니다."),
    ORDER_PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "O014", "주문 금액과 결제 승인 금액이 일치하지 않습니다."),
    CART_ITEM_DUPLICATED(HttpStatus.CONFLICT, "C001", "이미 장바구니에 담긴 상품입니다."),
    CART_ITEM_FORBIDDEN(HttpStatus.FORBIDDEN, "C003", "본인의 장바구니 항목이 아닙니다.")
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return httpStatus;
    }
}
