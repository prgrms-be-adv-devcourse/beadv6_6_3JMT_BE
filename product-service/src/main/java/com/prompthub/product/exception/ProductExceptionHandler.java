package com.prompthub.product.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.product.exception.enums.ProductErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ProductExceptionHandler {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(
		BusinessException exception,
		HttpServletRequest request
	) {
		com.prompthub.exception.ErrorCode errorCode = exception.getErrorCode();

		log.warn(
			"[{}] Product 비즈니스 예외가 발생했습니다. code={}, message={}",
			getRequestId(request),
			errorCode.getCode(),
			exception.getMessage()
		);

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleIllegalStateException(
		IllegalStateException exception,
		HttpServletRequest request
	) {
		ProductErrorCode errorCode = ProductErrorCode.PRODUCT_INVALID_STATUS;

		log.warn("[{}] Product 상태 오류가 발생했습니다. reason={}", getRequestId(request), exception.getMessage());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, exception.getMessage()));
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		ConstraintViolationException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ErrorResponse> handleValidationException(Exception exception, HttpServletRequest request) {
		ProductErrorCode errorCode = ProductErrorCode.INVALID_INPUT_VALUE;

		log.warn("[{}] Product 요청 값 검증에 실패했습니다. reason={}", getRequestId(request), exception.getMessage());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception, HttpServletRequest request) {
		ProductErrorCode errorCode = ProductErrorCode.INTERNAL_SERVER_ERROR;

		log.error("[{}] Product 예상하지 못한 서버 오류가 발생했습니다.", getRequestId(request), exception);

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode));
	}

	private String getRequestId(HttpServletRequest request) {
		String requestId = request.getHeader(REQUEST_ID_HEADER);

		if (requestId == null || requestId.isBlank()) {
			return "요청 ID 없음";
		}

		return requestId;
	}
}
