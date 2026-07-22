package com.prompthub.user.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();

        log.warn(
                "[{}] 비즈니스 예외가 발생했습니다. code={}, message={}",
                getRequestId(request),
                errorCode.getCode(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.VALIDATION_FAILED;

        log.warn("[{}] 요청 본문 검증에 실패했습니다. reason={}", getRequestId(request), exception.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.VALIDATION_FAILED;

        log.warn("[{}] 요청 값 검증에 실패했습니다. reason={}", getRequestId(request), exception.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.VALIDATION_FAILED;

        log.warn(
                "[{}] 요청 값의 타입이 올바르지 않습니다. name={}, value={}",
                getRequestId(request),
                exception.getName(),
                exception.getValue()
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.VALIDATION_FAILED;

        log.warn("[{}] 요청 본문을 읽을 수 없습니다. reason={}", getRequestId(request), exception.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.VALIDATION_FAILED;

        log.warn(
                "[{}] 필수 요청 파라미터가 누락되었습니다. parameterName={}",
                getRequestId(request),
                exception.getParameterName()
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeaderException(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = resolveMissingHeaderErrorCode(exception.getHeaderName());

        log.warn("[{}] 필수 요청 헤더가 누락되었습니다. headerName={}", getRequestId(request), exception.getHeaderName());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(NoResourceFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception exception,
            HttpServletRequest request
    ) {
        UserErrorCode errorCode = UserErrorCode.INTERNAL_SERVER_ERROR;

        log.error("[{}] 예상하지 못한 서버 오류가 발생했습니다.", getRequestId(request), exception);

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    private ErrorCode resolveMissingHeaderErrorCode(String headerName) {
        if (USER_ID_HEADER.equalsIgnoreCase(headerName)
                || USER_ROLE_HEADER.equalsIgnoreCase(headerName)) {
            return UserErrorCode.AUTH_FORBIDDEN;
        }

        return UserErrorCode.VALIDATION_FAILED;
    }

    private String getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        if (requestId == null || requestId.isBlank()) {
            return "요청 ID 없음";
        }

        return requestId;
    }
}
