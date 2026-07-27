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
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

	/**
	 * 매핑된 핸들러가 없는 경로 요청을 404로 응답한다.
	 *
	 * <p>이 핸들러가 없으면 아래 {@code Exception} 캐치올이 대신 잡아 500을 반환한다.
	 * 그러면 "주소를 잘못 썼다"와 "서버가 실제로 터졌다"가 같은 응답이 돼, 클라이언트도
	 * 모니터링도 둘을 구분하지 못한다.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(
		NoResourceFoundException exception,
		HttpServletRequest request
	) {
		ProductErrorCode errorCode = ProductErrorCode.ENDPOINT_NOT_FOUND;

		log.warn("[{}] 존재하지 않는 경로 요청입니다. path={}", getRequestId(request), exception.getResourcePath());

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
