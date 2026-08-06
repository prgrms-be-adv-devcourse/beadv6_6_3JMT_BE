package com.prompthub.admin.settlement.exception;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// GlobalExceptionHandler(LOWEST_PRECEDENCE)의 Exception.class 폴백보다 먼저 평가되도록 낮은 순서를 둔다.
@Slf4j
@RestControllerAdvice
@Order(0)
public class SettlementExceptionHandler {

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
}
