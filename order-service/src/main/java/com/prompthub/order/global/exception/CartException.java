package com.prompthub.order.global.exception;

public class CartException extends BusinessException {

    public CartException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CartException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
