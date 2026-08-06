package com.prompthub.payment.presentation;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.payment.application.exception.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class PaymentExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("결제 비즈니스 예외 - code={}, type={}", e.getErrorCode().getCode(), e.getClass().getSimpleName());
        return ResponseEntity
            .status(e.getErrorCode().getStatus())
            .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        log.warn("결제 요청 값 검증 실패 - code={}, type={}", PaymentErrorCode.INVALID_INPUT.getCode(), e.getClass().getSimpleName());
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse.of(PaymentErrorCode.INVALID_INPUT, message));
    }
}
