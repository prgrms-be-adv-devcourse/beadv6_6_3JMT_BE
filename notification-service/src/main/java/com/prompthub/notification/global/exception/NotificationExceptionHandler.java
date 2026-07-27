package com.prompthub.notification.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class NotificationExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException exception) {
        return ResponseEntity.status(exception.getErrorCode().getStatus()).body(ErrorResponse.of(exception.getErrorCode()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception exception) {
        return ResponseEntity.status(NotificationErrorCode.INVALID_INPUT_VALUE.getStatus())
            .body(ErrorResponse.of(NotificationErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        return ResponseEntity.status(NotificationErrorCode.INTERNAL_SERVER_ERROR.getStatus())
            .body(ErrorResponse.of(NotificationErrorCode.INTERNAL_SERVER_ERROR));
    }
}
