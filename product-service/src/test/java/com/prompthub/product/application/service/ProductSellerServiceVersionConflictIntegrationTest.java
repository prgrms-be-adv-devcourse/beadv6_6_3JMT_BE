package com.prompthub.product.application.service;
import com.prompthub.product.application.service.seller.ProductSellerService;
import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;
import com.prompthub.product.application.service.seller.ProductVersionChangePolicy;
import com.prompthub.product.application.service.seller.ProductVersionTransitionService;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.service.fileupload.TempFilePromoter;
import com.prompthub.product.application.usecase.inspection.ProductEventPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.infra.persistence.query.ProductJpaRepository;
import com.prompthub.product.infra.persistence.query.ProductRepositoryAdapter;
import com.prompthub.product.presentation.dto.request.product.ProductUpdateRequest;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V10 unique index 충돌이 실제 DB 예외 경로를 타는지, 그리고 그 요청이 승격한 영구 S3 object만
 * 보상 삭제되는지를 실제 Postgres + TempFilePromoter로 검증한다(PR 3, Codex 리뷰 지적 #3).
 *
 * <p>동시 요청 두 개를 실제 스레드로 띄우지 않는다 — "경쟁에서 진 요청"을 먼저 커밋된 승자 row가
 * 이미 있는 상태에서 나중에 같은 version을 만들려는 시도로 재현하면, 스레드 타이밍 없이도 같은
 * DB 제약 위반 경로를 결정적으로 재현할 수 있다.
 *
 * <p>{@code ProductSellerService}는 여기서 직접 {@code new}하지 않고 Spring 빈으로 주입받는다 —
 * {@code @Transactional} AOP는 프록시를 거쳐야만 걸리는데, 직접 생성하면 프록시가 없어 각 repository
 * 호출이 자기만의 트랜잭션으로 쪼개지고, TempFilePromoter의 커밋/롤백 보상도 걸리지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ProductRepositoryAdapter.class, TempFilePromoter.class, ProductInspectionRequestPublisher.class,
	ProductVersionChangePolicy.class, ProductVersionTransitionService.class, ProductSellerService.class
})
@ActiveProfiles("test")
class ProductSellerServiceVersionConflictIntegrationTest extends PostgresIntegrationTestSupport {

	private static final UUID SELLER_ID = UUID.randomUUID();

	@Autowired
	private ProductRepositoryAdapter productRepository;

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private ProductSellerService productSellerService;

	@MockitoBean
	private ObjectStorageGateway objectStorage;

	@MockitoBean
	private ProductEventPublisher productEventPublisher;

	// commit()으로 실제 커밋한 row는 @DataJpaTest의 기본 롤백 대상이 아니라 다음 테스트로 새어
	// 나간다 — 컨테이너를 테스트 실행 전체가 공유하므로 직접 지운다.
	private final List<UUID> committedProductIds = new ArrayList<>();

	@AfterEach
	void cleanUpCommittedFixtures() {
		productJpaRepository.deleteAllByIdInBatch(committedProductIds);
	}

	@Test
	@DisplayName("경쟁 요청이 먼저 선점한 다음 version과 부딪히면 DataIntegrityViolationException이 나고, 이번 요청이 승격한 영구 object만 삭제되며 temp 원본은 남는다")
	void losingRequest_rollsBackOnlyItsOwnPromotedObject() {
		Product onSale = Product.create(UUID.randomUUID(), SELLER_ID, promptContent("제목", 1000, "본문"));
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 0);
		productJpaRepository.saveAndFlush(onSale);

		// 경쟁 요청(메타데이터만 바꾼 PATCH)이 이미 2.1을 선점해 커밋했다. MAJOR 경로는
		// family.pendingReview() 가드에 먼저 걸려 DB까지 못 가므로, 가드가 없는 PATCH 충돌로
		// unique index 자체를 재현한다. status는 SUPERSEDED로 둔다 — ON_SALE로 두면
		// family.currentOnSale()이 onSale 대신 이 row를 고를 수 있어 충돌 재현이 흔들린다.
		Product winner = Product.create(UUID.randomUUID(), SELLER_ID, promptContent("승자", 1500, "본문"));
		ReflectionTestUtils.setField(winner, "parentId", onSale.getId());
		ReflectionTestUtils.setField(winner, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(winner, "patchVersion", (short) 1);
		ReflectionTestUtils.setField(winner, "status", ProductStatus.SUPERSEDED);
		productJpaRepository.saveAndFlush(winner);
		commit(); // 여기서 실제로 커밋해야 다음 save가 진짜 unique 위반을 낸다.
		committedProductIds.add(onSale.getId());
		committedProductIds.add(winner.getId());

		String tempThumbnailKey = "products/temp/" + SELLER_ID + "/thumbnail/loser.png";
		ProductUpdateRequest request = new ProductUpdateRequest(
			"패자", "PROMPT", "model2", "패자 설명", 1200, "본문",
			null, null, tempThumbnailKey, List.of(), List.of(), "패자의 변경 사유");

		// updateProduct()가 실제 @Transactional 프록시를 거쳐 하나의 트랜잭션으로 묶이므로,
		// 여기서 던진 예외가 나오는 시점엔 TempFilePromoter의 rollback 보상도 이미 끝나 있다.
		assertThatThrownBy(() -> productSellerService.updateProduct(SELLER_ID, onSale.getId(), request))
			.isInstanceOf(DataIntegrityViolationException.class);

		ArgumentCaptor<String> permanentKeyCaptor = ArgumentCaptor.forClass(String.class);
		verify(objectStorage).copy(eq(tempThumbnailKey), permanentKeyCaptor.capture());
		verify(objectStorage).delete(permanentKeyCaptor.getValue());
		verify(objectStorage, never()).delete(tempThumbnailKey);
	}

	private void commit() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
	}
}
