package com.prompthub.order.global.exception;

import com.prompthub.exception.BusinessException;

public class OrderException extends BusinessException {

    public OrderException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OrderException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
