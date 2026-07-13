package com.prompthub.product.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.enums.ReviewStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
class ProductJpaRepositoryTest {

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
	void findAllByFamilyRootIds_returnsRootAndChildren() {
		Product root = product(null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(root.getId(), ProductStatus.ON_SALE, (short) 2, (short) 0);
		Product unrelated = product(null, ProductStatus.ON_SALE, (short) 1, (short) 0);
		productJpaRepository.saveAll(List.of(root, child, unrelated));

		List<Product> result = productJpaRepository.findAllByFamilyRootIds(List.of(root.getId()));

		assertThat(result).extracting(Product::getId).containsExactlyInAnyOrder(root.getId(), child.getId());
	}

	private Product product(UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", null, null, List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
