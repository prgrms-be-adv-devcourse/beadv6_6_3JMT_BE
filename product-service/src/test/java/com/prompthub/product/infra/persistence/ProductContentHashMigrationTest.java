package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.model.vo.ProductContentHash;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

/**
 * V4 마이그레이션이 실제로 무엇을 만들었는지 검증한다.
 *
 * <p>백필 SQL이 Postgres {@code sha256()}을 쓰고 애플리케이션은 Java
 * {@code MessageDigest}를 쓴다. 둘이 어긋나면 기존 상품과 신규 상품의 해시 규칙이 달라져
 * 복제 탐지가 조용히 반쪽이 되므로, 두 값이 같은지를 여기서 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductContentHashMigrationTest extends PostgresIntegrationTestSupport {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("content_hash 컬럼이 만들어져 있다")
	void columnExists() {
		Object count = entityManager
			.createNativeQuery("""
				SELECT count(*) FROM information_schema.columns
				 WHERE table_schema = 'product_service'
				   AND table_name = 'product'
				   AND column_name = 'content_hash'
				""")
			.getSingleResult();

		assertThat(((Number) count).intValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("content_hash 인덱스가 부분 인덱스가 아니다 — PENDING_REVIEW도 비교 대상이다")
	void indexIsNotPartial() {
		Object indexDef = entityManager
			.createNativeQuery("""
				SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_product_content_hash'
				""")
			.getSingleResult();

		// WHERE 절이 붙으면 ON_SALE 같은 조건으로 좁혀졌다는 뜻이라, 동시에 올라온 복제본
		// 두 개(둘 다 PENDING_REVIEW)를 서로 잡지 못하게 된다.
		assertThat(indexDef.toString()).contains("content_hash").doesNotContain("WHERE");
	}

	@Test
	@DisplayName("Postgres sha256()이 Java로 계산한 해시와 같은 값을 낸다")
	void postgresHashMatchesJava() {
		// 백필 SQL이 쓰는 계산과 애플리케이션이 쓰는 계산이 같아야 한다.
		ProductContent content = promptContent("해시대조", 1000);
		Product product = productJpaRepository.save(
			Product.create(UUID.randomUUID(), UUID.randomUUID(), content));

		Object fromDb = entityManager
			.createNativeQuery("""
				SELECT encode(sha256(convert_to(content, 'UTF8')), 'hex')
				  FROM product WHERE id = :id
				""")
			.setParameter("id", product.getId())
			.getSingleResult();

		assertThat(fromDb.toString())
			.isEqualTo(ProductContentHash.of(content))
			.isEqualTo(product.getContentHash());
	}

	@Test
	@DisplayName("본문이 없는 유형은 content_hash가 비어 있다")
	void nullForTypesWithoutContent() {
		Product notion = productJpaRepository.save(
			Product.create(UUID.randomUUID(), UUID.randomUUID(), notionContent("노션상품", 1000)));

		Object stored = entityManager
			.createNativeQuery("SELECT content_hash FROM product WHERE id = :id")
			.setParameter("id", notion.getId())
			.getSingleResult();

		assertThat(stored).isNull();
	}
}
