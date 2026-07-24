package com.prompthub.ai.global.exception;

import com.prompthub.ai.settlement.domain.exception.InvalidRunStateException;
import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiException.class)
    public ResponseEntity<?> handleAiException(
            AiException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        logBusinessException(errorCode, exception);
        if (acceptsEventStream(request)) {
            return ResponseEntity.status(errorCode.getStatus()).build();
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        logBusinessException(errorCode, exception);
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
        log.warn("AI 질문 검증 실패 - type={}", exception.getClass().getSimpleName());
        ErrorCode errorCode = AiErrorCode.INVALID_CHAT_MESSAGE;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(InvalidRunStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRunState(InvalidRunStateException exception) {
        log.error("AI run 상태 전이 위반 - category={}", exception.getClass().getSimpleName());
        ErrorCode errorCode = AiErrorCode.AI_INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        log.error("예상하지 못한 AI 서비스 오류 - category={}", exception.getClass().getSimpleName());
        ErrorCode errorCode = AiErrorCode.AI_INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    private static String exceptionCategory(Throwable exception) {
        Throwable cause = exception.getCause();
        return (cause == null ? exception : cause).getClass().getSimpleName();
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && MediaType.parseMediaTypes(accept).stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    private static void logBusinessException(ErrorCode errorCode, BusinessException exception) {
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("AI 비즈니스 예외 - code={}, category={}",
                    errorCode.getCode(), exceptionCategory(exception));
        } else {
            log.warn("AI 비즈니스 예외 - code={}, category={}",
                    errorCode.getCode(), exceptionCategory(exception));
        }
    }
}
