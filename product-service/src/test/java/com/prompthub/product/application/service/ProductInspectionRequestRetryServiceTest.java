package com.prompthub.product.application.service;
import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;
import com.prompthub.product.application.service.inspection.ProductInspectionRequestRetryService;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 오래 대기한 검수 요청 1회 재발행 흐름을 검증한다(2026-08-05 로드맵 PR4). DB 조건부 UPDATE 자체의
 * 원자성(동시 선점 방지)은 {@code ProductJpaRepositoryTest}의 실제 Postgres 통합 테스트가 검증하고,
 * 여기서는 claim 결과에 따른 서비스 흐름 분기만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductInspectionRequestRetryServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 11, 0, 0);

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductInspectionRequestPublisher productInspectionRequestPublisher;

	@InjectMocks
	private ProductInspectionRequestRetryService productInspectionRequestRetryService;

	@Test
	@DisplayName("선점에 성공하면 상품을 다시 조회해 재발행한다")
	void retryStaleInspectionRequest_claimed_publishesInspectionRequest() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		given(productRepository.claimInspectionRequestRetry(PRODUCT_ID, CUTOFF)).willReturn(true);
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

		productInspectionRequestRetryService.retryStaleInspectionRequest(PRODUCT_ID, CUTOFF);

		then(productInspectionRequestPublisher).should().publish(product);
	}

	@Test
	@DisplayName("이미 다른 실행이 선점했으면(claim 실패) 조용히 건너뛴다 — 재발행은 총 1회만 일어난다")
	void retryStaleInspectionRequest_alreadyClaimedByAnotherRun_skipsSilently() {
		given(productRepository.claimInspectionRequestRetry(PRODUCT_ID, CUTOFF)).willReturn(false);

		productInspectionRequestRetryService.retryStaleInspectionRequest(PRODUCT_ID, CUTOFF);

		then(productRepository).should(never()).findById(eq(PRODUCT_ID));
		then(productInspectionRequestPublisher).should(never()).publish(any(Product.class));
	}

	@Test
	@DisplayName("선점 후 상품을 찾을 수 없으면(동시 삭제 등) 발행 없이 넘어간다")
	void retryStaleInspectionRequest_claimedButProductMissing_doesNotPublish() {
		given(productRepository.claimInspectionRequestRetry(PRODUCT_ID, CUTOFF)).willReturn(true);
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		productInspectionRequestRetryService.retryStaleInspectionRequest(PRODUCT_ID, CUTOFF);

		then(productInspectionRequestPublisher).should(never()).publish(any(Product.class));
	}

	@Test
	@DisplayName("stale 후보 조회는 리포지토리에 그대로 위임한다")
	void findStaleInspectionRequestCandidateIds_delegatesToRepository() {
		given(productRepository.findStaleInspectionRequestCandidateIds(CUTOFF, 50)).willReturn(List.of(PRODUCT_ID));

		List<UUID> result = productInspectionRequestRetryService.findStaleInspectionRequestCandidateIds(CUTOFF, 50);

		assertThat(result).containsExactly(PRODUCT_ID);
	}
}
