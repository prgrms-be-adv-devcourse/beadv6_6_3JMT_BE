package com.prompthub.product.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.product.domain.exception.ProductInvalidStatusException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Spring이 NoResourceFoundException을 이 핸들러로 보내는지까지는 전체 컨텍스트가 필요해
 * 여기서 검증하지 않는다. 그 부분은 배포 환경에서 없는 경로를 호출해 확인한다.
 * 이 테스트가 고정하는 것은 "그 예외가 오면 404 + SYS002로 응답한다"이다.
 */
class ProductExceptionHandlerTest {

	private final ProductExceptionHandler handler = new ProductExceptionHandler();

	@Test
	@DisplayName("매핑되지 않은 경로는 500이 아니라 404로 응답한다")
	void handleNoResourceFound_returns404() {
		NoResourceFoundException exception = new NoResourceFoundException(
			HttpMethod.POST, "/internal/search/reindex", "/internal/search/reindex");

		ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ProductErrorCode.ENDPOINT_NOT_FOUND.getCode());
	}

	/**
	 * ES 인프라 장애(ElasticsearchProductSearchIndexer 등)를 포함해 도메인 상태 가드와
	 * 무관한 {@code IllegalStateException}은 이 핸들러가 더 이상 잡지 않는다 — Spring이
	 * 더 구체적인 핸들러가 없는 예외를 위 handleException(500)으로 보낸다.
	 */
	@Test
	@DisplayName("도메인 상태 가드 위반(ProductInvalidStatusException)은 409/P006으로 응답한다")
	void handleProductInvalidStatusException_returns409() {
		ProductInvalidStatusException exception =
			new ProductInvalidStatusException("PENDING_REVIEW 상태의 상품만 승인할 수 있습니다. current=ON_SALE");

		ResponseEntity<ErrorResponse> response = handler.handleProductInvalidStatusException(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ProductErrorCode.PRODUCT_INVALID_STATUS.getCode());
	}

	@Test
	@DisplayName("예상하지 못한 예외는 500으로 응답한다")
	void handleException_returns500() {
		ResponseEntity<ErrorResponse> response = handler.handleException(new RuntimeException("boom"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ProductErrorCode.INTERNAL_SERVER_ERROR.getCode());
	}

	@Test
	@DisplayName("family-version unique 제약 위반은 실제 Hibernate 예외 체인에서도 P009/409로 변환된다")
	void handleDataIntegrityViolation_familyVersionConflict_returnsP009() {
		DataIntegrityViolationException exception = new DataIntegrityViolationException(
			"insert failed", new ConstraintViolationException("duplicate key", null, "uk_product_family_version"));

		ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ProductErrorCode.PRODUCT_VERSION_CONFLICT.getCode());
	}

	@Test
	@DisplayName("다른 제약 위반은 P009로 오인하지 않고 500으로 응답한다")
	void handleDataIntegrityViolation_otherConstraint_returns500() {
		DataIntegrityViolationException exception = new DataIntegrityViolationException(
			"insert failed", new ConstraintViolationException("not null", null, "product_seller_id_not_null"));

		ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ProductErrorCode.INTERNAL_SERVER_ERROR.getCode());
	}
}
