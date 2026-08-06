package com.prompthub.settlement.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.exception.SettlementSourceLineAlreadySettledException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("비즈니스 예외 처리 실패 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());
        } else {
            log.warn("비즈니스 예외 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(SettlementBatchInvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleSettlementBatchInvalidState(
            SettlementBatchInvalidStateException exception) {
        log.warn("정산 배치 상태 충돌 - code={}, type={}", SettlementErrorCode.SETTLEMENT_BATCH_INVALID_STATE.getCode(), exception.getClass().getSimpleName());
        ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_BATCH_INVALID_STATE;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(SettlementSourceLineAlreadySettledException.class)
    public ResponseEntity<ErrorResponse> handleSettlementSourceLineAlreadySettled(
            SettlementSourceLineAlreadySettledException exception) {
        log.warn("정산 소스 라인 중복 정산 - code={}, type={}", SettlementErrorCode.SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED.getCode(), exception.getClass().getSimpleName());
        ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
        log.warn("요청 값 검증 실패 - code={}, type={}", SettlementErrorCode.INVALID_INPUT_VALUE.getCode(), exception.getClass().getSimpleName());
        ErrorCode errorCode = SettlementErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        log.error("예상하지 못한 서버 오류 - code={}, type={}", SettlementErrorCode.INTERNAL_SERVER_ERROR.getCode(), exception.getClass().getSimpleName());
        ErrorCode errorCode = SettlementErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }
}
