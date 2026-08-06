package com.prompthub.admin.global.exception;

import com.prompthub.admin.settlement.exception.SettlementAlreadyCancelledException;
import com.prompthub.admin.settlement.exception.SettlementAlreadyPaidException;
import com.prompthub.admin.settlement.exception.SettlementInvalidStateException;
import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

	@ExceptionHandler(SettlementInvalidStateException.class)
	public ResponseEntity<ErrorResponse> handleSettlementInvalidState(SettlementInvalidStateException exception) {
		log.warn("정산 상태 전이 충돌 - code={}, type={}", AdminErrorCode.SETTLEMENT_INVALID_STATE.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.SETTLEMENT_INVALID_STATE;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(SettlementAlreadyPaidException.class)
	public ResponseEntity<ErrorResponse> handleSettlementAlreadyPaid(SettlementAlreadyPaidException exception) {
		log.warn("정산 취소 거부 - code={}, type={}", AdminErrorCode.SETTLEMENT_ALREADY_PAID.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.SETTLEMENT_ALREADY_PAID;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(SettlementAlreadyCancelledException.class)
	public ResponseEntity<ErrorResponse> handleSettlementAlreadyCancelled(
		SettlementAlreadyCancelledException exception) {
		log.warn("정산 취소 거부 - code={}, type={}", AdminErrorCode.SETTLEMENT_ALREADY_CANCELLED.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.SETTLEMENT_ALREADY_CANCELLED;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException exception) {
		log.warn("상태 전이 충돌 - code={}, type={}", AdminErrorCode.PRODUCT_INVALID_STATUS.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.PRODUCT_INVALID_STATUS;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		ConstraintViolationException.class,
		HttpMessageNotReadableException.class,
		MissingRequestHeaderException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
		log.warn("요청 값 검증 실패 - code={}, type={}", AdminErrorCode.INVALID_INPUT_VALUE.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.INVALID_INPUT_VALUE;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception) {
		log.error("예상하지 못한 서버 오류 - code={}, type={}", AdminErrorCode.INTERNAL_SERVER_ERROR.getCode(), exception.getClass().getSimpleName());
		ErrorCode errorCode = AdminErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}
}
