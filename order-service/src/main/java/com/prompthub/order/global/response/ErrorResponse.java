package com.prompthub.order.global.response;

import com.prompthub.order.global.exception.ErrorCode;

public record ErrorResponse(
        boolean success,
        Object data,
        String message,
        String code
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(false, null, errorCode.getMessage(), errorCode.getCode());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(false, null, message, errorCode.getCode());
    }
}
