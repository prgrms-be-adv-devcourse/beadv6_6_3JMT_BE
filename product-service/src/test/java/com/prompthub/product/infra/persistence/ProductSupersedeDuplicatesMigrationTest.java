package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V9 정리 SQL의 판정 로직을 검증한다(#699).
 *
 * <p>마이그레이션 자체는 컨텍스트 기동 때 이미 실행됐으므로(그 시점 테이블은 비어 있다),
 * 오염 데이터를 만든 뒤 <b>같은 SQL 파일을 다시 실행</b>해 "가족 내 다중 ON_SALE 중 최고
 * 버전만 남긴다"는 판정이 맞는지 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductSupersedeDuplicatesMigrationTest extends PostgresIntegrationTestSupport {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("가족 내 다중 ON_SALE은 최고 버전만 남고 나머지는 SUPERSEDED가 된다")
	void supersedesLowerVersionsOnly() throws Exception {
		Product v1 = onSale(1);
		Product v2 = onSale(2);
		ReflectionTestUtils.setField(v2, "parentId", v1.getId());
		Product single = onSale(1);
		productJpaRepository.saveAndFlush(v1);
		productJpaRepository.saveAndFlush(v2);
		productJpaRepository.saveAndFlush(single);

		runV9();
		entityManager.clear();

		assertThat(statusOf(v1)).isEqualTo(ProductStatus.SUPERSEDED);
		assertThat(statusOf(v2)).isEqualTo(ProductStatus.ON_SALE);
		assertThat(statusOf(single)).isEqualTo(ProductStatus.ON_SALE);
	}

	private void runV9() throws Exception {
		String sql;
		try (var in = getClass().getResourceAsStream("/db/migration/V9__supersede_duplicate_on_sale_versions.sql")) {
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		entityManager.createNativeQuery(sql).executeUpdate();
	}

	private ProductStatus statusOf(Product product) {
		return productJpaRepository.findById(product.getId()).orElseThrow().getStatus();
	}

	private Product onSale(int majorVersion) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(product, "majorVersion", (short) majorVersion);
		return product;
	}
}
