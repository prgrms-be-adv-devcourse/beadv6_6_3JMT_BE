package com.prompthub.product.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.product.exception.enums.ProductErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ProductExceptionHandler {

	private static final String FAMILY_VERSION_UNIQUE_CONSTRAINT = "uk_product_family_version";

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
		com.prompthub.exception.ErrorCode errorCode = exception.getErrorCode();
		log.warn("Product 비즈니스 예외 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleIllegalStateException(
		IllegalStateException exception
	) {
		ProductErrorCode errorCode = ProductErrorCode.PRODUCT_INVALID_STATUS;

		log.warn("Product 상태 오류 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, exception.getMessage()));
	}

	/** family-version unique index 위반만 P009/409로 변환한다 — 그 외 DB 제약 위반은 기존 500 그대로 둔다. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
		if (!violatesFamilyVersionUnique(exception)) {
			ProductErrorCode errorCode = ProductErrorCode.INTERNAL_SERVER_ERROR;
			log.error("Product DB 제약 위반 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());
			return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
		}

		ProductErrorCode errorCode = ProductErrorCode.PRODUCT_VERSION_CONFLICT;
		log.warn("동시 버전 생성 충돌 - code={}", errorCode.getCode());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode));
	}

	private boolean violatesFamilyVersionUnique(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
				&& FAMILY_VERSION_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		ConstraintViolationException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ErrorResponse> handleValidationException(Exception exception) {
		ProductErrorCode errorCode = ProductErrorCode.INVALID_INPUT_VALUE;

		log.warn("Product 요청 값 검증 실패 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());

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
		NoResourceFoundException exception
	) {
		ProductErrorCode errorCode = ProductErrorCode.ENDPOINT_NOT_FOUND;

		log.warn("존재하지 않는 경로 요청 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception) {
		ProductErrorCode errorCode = ProductErrorCode.INTERNAL_SERVER_ERROR;

		log.error("Product 예상하지 못한 서버 오류 - code={}, type={}", errorCode.getCode(), exception.getClass().getSimpleName());

		return ResponseEntity
			.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode));
	}

}
