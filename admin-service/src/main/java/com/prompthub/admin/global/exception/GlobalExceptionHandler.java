package com.prompthub.admin.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 도메인별 {@code @RestControllerAdvice}(예: {@code SettlementExceptionHandler})가 먼저 평가되도록
 * 가장 낮은 우선순위로 둔다. Spring은 여러 advice 빈 중 먼저 매칭되는 빈에서 멈추므로, 이 순서가
 * 없으면 여기의 {@code Exception.class} 폴백이 도메인 순수 예외까지 가로챈다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
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
