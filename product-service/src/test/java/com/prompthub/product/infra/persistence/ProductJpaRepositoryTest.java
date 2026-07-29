package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

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
	void findChangedFamilyRootIds_삭제된_상품은_제외한다() {
		Product deleted = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		ReflectionTestUtils.setField(deleted, "updatedAt", LocalDateTime.now());
		ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
		productJpaRepository.save(deleted);

		List<UUID> result = productJpaRepository.findChangedFamilyRootIds(LocalDateTime.now().minusMinutes(1));

		assertThat(result).isEmpty();
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

	private Product product(UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
