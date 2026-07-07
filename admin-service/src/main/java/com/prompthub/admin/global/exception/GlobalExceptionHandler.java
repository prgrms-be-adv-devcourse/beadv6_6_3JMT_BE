package com.prompthub.admin.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
			log.error("비즈니스 예외(5xx) - code={}", errorCode.getCode(), exception);
		} else {
			log.warn("비즈니스 예외 - code={}, message={}", errorCode.getCode(), exception.getMessage());
		}
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, exception.getMessage()));
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		ConstraintViolationException.class,
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
		log.warn("요청 값 검증 실패 - reason={}", exception.getMessage());
		ErrorCode errorCode = AdminErrorCode.INVALID_INPUT_VALUE;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception) {
		log.error("예상하지 못한 서버 오류", exception);
		ErrorCode errorCode = AdminErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}
}
