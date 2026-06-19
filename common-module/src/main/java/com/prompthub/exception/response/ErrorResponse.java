package com.prompthub.exception.response;

import com.prompthub.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

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