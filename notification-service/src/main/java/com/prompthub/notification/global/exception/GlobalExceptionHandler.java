package com.prompthub.notification.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handle(BusinessException exception) {
        return ResponseEntity.status(exception.getErrorCode().getStatus()).body(ErrorResponse.of(exception.getErrorCode()));
    }
}
