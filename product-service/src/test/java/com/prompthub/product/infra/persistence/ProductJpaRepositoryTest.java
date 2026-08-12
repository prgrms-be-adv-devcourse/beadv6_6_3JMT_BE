package com.prompthub.product.infra.persistence;
import com.prompthub.product.infra.persistence.query.ProductJpaRepository;
import com.prompthub.product.infra.persistence.review.ReviewJpaRepository;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ReviewStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

// replace = NONE — @DataJpaTest 기본값은 내장 DB로 갈아끼우는데, 여기서는
// PostgresIntegrationTestSupport가 띄운 컨테이너를 그대로 써야 한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductJpaRepositoryTest extends PostgresIntegrationTestSupport {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private ReviewJpaRepository reviewJpaRepository;

	@Test
	void findPublicProducts_aggregatesRatingAcrossFamilyRoot() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product current = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		productJpaRepository.saveAll(List.of(root, current));

		Review review = Review.create(UUID.randomUUID(), root, (short) 5);
		reviewJpaRepository.save(review);

		List<ProductListProjection> result = productJpaRepository.findPublicProducts(
			"", "all", "popular", ProductStatus.ON_SALE, ReviewStatus.ACTIVE,
			org.springframework.data.domain.PageRequest.of(0, 20)
		);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).rating()).isEqualTo(5.0);
	}

	@Test
	void getAverageRatings_aggregatesPerFamilyRoot_andOmitsFamiliesWithoutReviews() {
		Product familyA = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		Product familyB = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.saveAll(List.of(familyA, familyB));

		reviewJpaRepository.save(Review.create(UUID.randomUUID(), familyA, (short) 4));
		reviewJpaRepository.save(Review.create(UUID.randomUUID(), familyA, (short) 2));

		Map<UUID, Double> result =
			productJpaRepository.getAverageRatings(List.of(familyA.getId(), familyB.getId()));

		assertThat(result).containsEntry(familyA.getId(), 3.0);
		assertThat(result).doesNotContainKey(familyB.getId());
	}

	@Test
	void getAverageRatings_emptyIds_returnsEmptyMapWithoutQuerying() {
		Map<UUID, Double> result = productJpaRepository.getAverageRatings(List.of());

		assertThat(result).isEmpty();
	}

	@Test
	void review_같은_사용자가_같은_상품에_리뷰를_두_개_저장하면_DB_제약에_막힌다() {
		Product root = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.save(root);

		UUID userId = UUID.randomUUID();
		reviewJpaRepository.saveAndFlush(Review.create(userId, root, (short) 4));

		// upsert 로직이 경합에 뚫려도 DB가 두 번째 저장을 막아야 한다 (V7 uk_review_product_user).
		// 막지 못하면 이 사용자의 평점이 두 번 세어져 평균이 왜곡된다
		assertThatThrownBy(() -> reviewJpaRepository.saveAndFlush(Review.create(userId, root, (short) 5)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void getAverageRating_HIDDEN과_삭제된_리뷰는_평균에서_제외한다() {
		Product root = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.save(root);

		reviewJpaRepository.save(Review.create(UUID.randomUUID(), root, (short) 4));

		Review hidden = Review.create(UUID.randomUUID(), root, (short) 1);
		ReflectionTestUtils.setField(hidden, "status", ReviewStatus.HIDDEN);
		reviewJpaRepository.save(hidden);

		Review deleted = Review.create(UUID.randomUUID(), root, (short) 1);
		ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
		reviewJpaRepository.save(deleted);

		double result = productJpaRepository.getAverageRating(root.getId());

		// 살아있는 4점 하나만 세야 한다. 1점짜리 둘이 섞이면 2.0으로 내려간다
		assertThat(result).isEqualTo(4.0);
	}

	@Test
	void sumSalesCountByFamilyRootId_여러_버전에_흩어진_판매수를_합산하고_삭제된_버전은_제외한다() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		ReflectionTestUtils.setField(root, "salesCount", 5);
		Product current = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		ReflectionTestUtils.setField(current, "salesCount", 2);
		Product deletedVersion = product(root.getId(), ProductStatus.STOPPED, (short) 1, (short) 1);
		ReflectionTestUtils.setField(deletedVersion, "salesCount", 99);
		ReflectionTestUtils.setField(deletedVersion, "deletedAt", LocalDateTime.now());
		productJpaRepository.saveAll(List.of(root, current, deletedVersion));

		long result = productJpaRepository.sumSalesCountByFamilyRootId(root.getId());

		// 구매는 그 시점의 버전 row에 붙으므로, family 전체를 합쳐야 실제 판매수가 나온다
		assertThat(result).isEqualTo(7L);
	}

	@Test
	void sumViewCountByFamilyRootId_aggregatesAcrossVersionsAndExcludesDeleted() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		ReflectionTestUtils.setField(root, "viewCount", 7);
		Product current = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		ReflectionTestUtils.setField(current, "viewCount", 3);
		Product deletedVersion = product(root.getId(), ProductStatus.STOPPED, (short) 1, (short) 1);
		ReflectionTestUtils.setField(deletedVersion, "viewCount", 100);
		ReflectionTestUtils.setField(deletedVersion, "deletedAt", java.time.LocalDateTime.now());
		productJpaRepository.saveAll(List.of(root, current, deletedVersion));

		long result = productJpaRepository.sumViewCountByFamilyRootId(root.getId());

		assertThat(result).isEqualTo(10L);
	}

	@Test
	void findAllByFamilyRootIds_returnsRootAndChildren() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		Product unrelated = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.saveAll(List.of(root, child, unrelated));

		List<Product> result = productJpaRepository.findAllByFamilyRootIds(List.of(root.getId()));

		assertThat(result).extracting(Product::getId).containsExactlyInAnyOrder(root.getId(), child.getId());
	}

	@Test
	void findChangedFamilyRootIds_변경이_없으면_빈_목록을_반환한다() {
		Product product = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(product, "updatedAt", LocalDateTime.now().minusHours(1));
		productJpaRepository.save(product);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		assertThat(result).isEmpty();
	}

	@Test
	void findChangedFamilyRootIds_자식_버전이_바뀌어도_family_루트_id를_반환한다() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		ReflectionTestUtils.setField(root, "updatedAt", LocalDateTime.now().minusHours(1));
		ReflectionTestUtils.setField(child, "updatedAt", LocalDateTime.now());
		productJpaRepository.saveAll(List.of(root, child));

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		// 바뀐 건 child지만 재조정 단위는 family이므로 루트 id가 나와야 한다
		assertThat(result).containsExactly(root.getId());
	}

	@Test
	void findChangedFamilyRootIds_리뷰가_바뀌면_그_family도_대상에_포함한다() {
		Product root = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(root, "updatedAt", LocalDateTime.now().minusHours(1));
		productJpaRepository.save(root);

		Review review = Review.create(UUID.randomUUID(), root, (short) 5);
		reviewJpaRepository.save(review);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		// 상품 자체는 안 바뀌었지만 평점이 달라졌으므로 재색인 대상이다
		assertThat(result).containsExactly(root.getId());
	}

	@Test
	void findChangedFamilyRootIds_삭제된_리뷰도_포함한다() {
		Product root = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(root, "updatedAt", LocalDateTime.now().minusHours(1));
		productJpaRepository.save(root);

		Review deletedReview = Review.create(UUID.randomUUID(), root, (short) 5);
		ReflectionTestUtils.setField(deletedReview, "deletedAt", LocalDateTime.now());
		reviewJpaRepository.save(deletedReview);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		// 리뷰 삭제로 평균 평점이 달라지므로, 삭제된 review row도 변경 감지 대상이어야 한다
		assertThat(result).containsExactly(root.getId());
	}

	@Test
	void findChangedFamilyRootIds_child_version에_달린_리뷰도_family_루트_id를_반환한다() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		ReflectionTestUtils.setField(root, "updatedAt", LocalDateTime.now().minusHours(1));
		ReflectionTestUtils.setField(child, "updatedAt", LocalDateTime.now().minusHours(1));
		productJpaRepository.saveAll(List.of(root, child));

		Review review = Review.create(UUID.randomUUID(), child, (short) 5);
		reviewJpaRepository.save(review);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		// review.product가 child여도 ES 문서 ID는 family root이므로 root id로 나와야 한다
		assertThat(result).containsExactly(root.getId());
	}

	@Test
	void findChangedFamilyRootIds_삭제된_상품도_포함한다() {
		// softDelete() 자체가 재조정이 필요한 변경이다 — 삭제된 family를 재조정 대상에서
		// 빼면 ES에 이미 색인된 문서가 정리되지 않고 그대로 남는다.
		Product deleted = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(deleted, "updatedAt", LocalDateTime.now());
		ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
		productJpaRepository.save(deleted);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		assertThat(result).containsExactly(deleted.getId());
	}

	@Test
	void findDuplicateOfProductId_flagsLaterSubmission_notEarlierOne() {
		UUID sellerA = UUID.randomUUID();
		UUID sellerB = UUID.randomUUID();
		String content = "복제 탐지 대상 원문";
		Product original = Product.create(UUID.randomUUID(), sellerA, promptContent("원본", 1000, content));
		ReflectionTestUtils.setField(original, "status", ProductStatus.ON_SALE);
		Product duplicate = Product.create(UUID.randomUUID(), sellerB, promptContent("복제", 1000, content));
		ReflectionTestUtils.setField(duplicate, "status", ProductStatus.PENDING_REVIEW);
		try {
			productJpaRepository.saveAndFlush(original);
			commit();

			beginNewTransaction();
			productJpaRepository.saveAndFlush(duplicate);
			commit();

			assertThat(productJpaRepository.findDuplicateOfProductId(
				duplicate.getId(), duplicate.getContentHash(), sellerB))
				.contains(original.getId());

			assertThat(productJpaRepository.findDuplicateOfProductId(
				original.getId(), original.getContentHash(), sellerA))
				.isEmpty();
		} finally {
			beginNewTransaction();
			productJpaRepository.deleteAllById(List.of(original.getId(), duplicate.getId()));
			commit();
			TestTransaction.start();
		}
	}

	@Test
	void findDuplicateOfProductId_excludesSameSeller() {
		UUID seller = UUID.randomUUID();
		String content = "동일 판매자 재제출 원문";
		Product v1 = Product.create(UUID.randomUUID(), seller, promptContent("v1", 1000, content));
		ReflectionTestUtils.setField(v1, "status", ProductStatus.ON_SALE);
		Product v2 = Product.create(UUID.randomUUID(), seller, promptContent("v2", 1000, content));
		ReflectionTestUtils.setField(v2, "status", ProductStatus.PENDING_REVIEW);
		try {
			productJpaRepository.saveAndFlush(v1);
			commit();

			beginNewTransaction();
			productJpaRepository.saveAndFlush(v2);
			commit();

			Optional<UUID> result =
				productJpaRepository.findDuplicateOfProductId(v2.getId(), v2.getContentHash(), seller);

			assertThat(result).isEmpty();
		} finally {
			beginNewTransaction();
			productJpaRepository.deleteAllById(List.of(v1.getId(), v2.getId()));
			commit();
			TestTransaction.start();
		}
	}

	private void commit() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
	}

	private void beginNewTransaction() {
		TestTransaction.start();
	}

	// stale 재발행 조건부 UPDATE의 원자성 검증(2026-08-05 로드맵 PR4) — 두 실행이 같은 상품을
	// 경쟁해도 발행 권리(claim)는 총 1회만 성립해야 한다. 실제 스레드 없이도 "먼저 커밋된 claim"
	// 다음에 같은 조건으로 다시 claim을 시도하는 순서로 같은 경쟁 결과를 결정적으로 재현한다
	// (ProductSellerServiceVersionConflictIntegrationTest와 같은 방식).
	@Test
	void claimInspectionRequestRetry_staleCandidate_claimsOnceAndSecondAttemptFails() {
		LocalDateTime cutoff = LocalDateTime.now();
		Product pending = product(null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
		ReflectionTestUtils.setField(pending, "inspectionRequestedAt", cutoff.minusMinutes(11));
		ReflectionTestUtils.setField(pending, "inspectionRequestRetryCount", 0);
		productJpaRepository.saveAndFlush(pending);

		boolean firstClaim = productJpaRepository.claimInspectionRequestRetry(pending.getId(), cutoff);
		boolean secondClaim = productJpaRepository.claimInspectionRequestRetry(pending.getId(), cutoff);

		assertThat(firstClaim).isTrue();
		assertThat(secondClaim).isFalse();
	}

	@Test
	void claimInspectionRequestRetry_notYetStale_doesNotClaim() {
		LocalDateTime cutoff = LocalDateTime.now();
		Product pending = product(null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
		ReflectionTestUtils.setField(pending, "inspectionRequestedAt", cutoff.minusMinutes(1));
		ReflectionTestUtils.setField(pending, "inspectionRequestRetryCount", 0);
		productJpaRepository.saveAndFlush(pending);

		boolean claimed = productJpaRepository.claimInspectionRequestRetry(pending.getId(), cutoff.minusMinutes(10));

		assertThat(claimed).isFalse();
	}

	@Test
	void findStaleInspectionRequestCandidateIds_returnsOnlyPendingReviewRetryZeroBeforeCutoff() {
		// queryCutoff = 스케줄러가 넘기는 "now - staleAfter" 기준값. stale은 이 기준보다 이전에
		// 요청됐고, fresh는 이 기준 이후(더 최근)에 요청됐다 — cutoff 자체를 기준 시각으로 겹쳐
		// 쓰면 두 케이스를 구분하지 못한다.
		LocalDateTime queryCutoff = LocalDateTime.now().minusMinutes(10);
		Product stale = product(null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
		ReflectionTestUtils.setField(stale, "inspectionRequestedAt", queryCutoff.minusMinutes(1));
		Product fresh = product(null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
		ReflectionTestUtils.setField(fresh, "inspectionRequestedAt", queryCutoff.plusMinutes(9));
		Product alreadyRetried = product(null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
		ReflectionTestUtils.setField(alreadyRetried, "inspectionRequestedAt", queryCutoff.minusMinutes(10));
		ReflectionTestUtils.setField(alreadyRetried, "inspectionRequestRetryCount", 1);
		Product notPending = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(notPending, "inspectionRequestedAt", queryCutoff.minusMinutes(20));
		productJpaRepository.saveAll(List.of(stale, fresh, alreadyRetried, notPending));

		List<UUID> result = productJpaRepository.findStaleInspectionRequestCandidateIds(queryCutoff, 50);

		assertThat(result).containsExactly(stale.getId());
	}

	private Product product(UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
