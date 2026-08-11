package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V10의 {@code uk_product_family_version} unique index를 검증한다(PR 3). 컨텍스트 기동 때
 * Flyway가 이미 이 index를 만들어뒀으므로, 여기서는 그 제약이 실제로 걸려 있는지만 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductFamilyVersionUniqueMigrationTest extends PostgresIntegrationTestSupport {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Test
	@DisplayName("같은 family의 동일 major.patch를 동시에 저장하면 제약 위반이 난다")
	void rejectsDuplicateFamilyVersion() {
		Product root = product(null, (short) 2, (short) 0);
		productJpaRepository.saveAndFlush(root);
		Product duplicate = product(root.getId(), (short) 2, (short) 0);

		assertThatThrownBy(() -> productJpaRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("다른 family의 같은 major.patch는 허용된다")
	void allowsSameVersionAcrossDifferentFamilies() {
		productJpaRepository.saveAndFlush(product(null, (short) 1, (short) 0));

		assertThatCode(() -> productJpaRepository.saveAndFlush(product(null, (short) 1, (short) 0)))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("REJECTED 동일 row를 그대로 다시 저장(update)하는 것은 제약에 막히지 않는다")
	void updatingSameRowDoesNotViolate() {
		Product product = product(null, (short) 3, (short) 0);
		productJpaRepository.saveAndFlush(product);
		ReflectionTestUtils.setField(product, "name", "보정된 제목");

		assertThatCode(() -> productJpaRepository.saveAndFlush(product)).doesNotThrowAnyException();
	}

	private Product product(UUID parentId, short majorVersion, short patchVersion) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
