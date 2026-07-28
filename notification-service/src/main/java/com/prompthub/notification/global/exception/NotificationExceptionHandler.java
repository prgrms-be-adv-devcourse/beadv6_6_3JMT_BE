package com.prompthub.notification.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Slf4j
public class NotificationExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException exception) {
        log.warn("알림 비즈니스 예외 - code={}, type={}",
                exception.getErrorCode().getCode(), exception.getClass().getSimpleName());
        return ResponseEntity.status(exception.getErrorCode().getStatus()).body(ErrorResponse.of(exception.getErrorCode()));
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<ErrorResponse> invalidInput(Exception exception) {
        log.warn("알림 요청 값 검증 실패 - code={}, type={}",
                NotificationErrorCode.INVALID_INPUT_VALUE.getCode(), exception.getClass().getSimpleName());
        return ResponseEntity.status(NotificationErrorCode.INVALID_INPUT_VALUE.getStatus())
            .body(ErrorResponse.of(NotificationErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error("예상하지 못한 알림 서비스 오류 - code={}, type={}",
                NotificationErrorCode.INTERNAL_SERVER_ERROR.getCode(), exception.getClass().getSimpleName());
        return ResponseEntity.status(NotificationErrorCode.INTERNAL_SERVER_ERROR.getStatus())
            .body(ErrorResponse.of(NotificationErrorCode.INTERNAL_SERVER_ERROR));
    }
}
