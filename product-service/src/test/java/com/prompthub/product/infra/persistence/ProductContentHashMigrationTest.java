package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.model.vo.ProductContentHash;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;

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
	@DisplayName("Postgres sha256()이 정규화(공백 축약+trim+소문자화) 후 Java와 같은 해시를 낸다")
	void postgresHashMatchesJava() {
		// V8 백필 SQL의 정규화 계산과 ProductContentHash.normalize()가 같아야 한다.
		// 정규화가 실제로 값을 바꾸는 입력(연속 공백·대문자 포함)으로 검증한다.
		ProductContent content = promptContent("해시대조", 1000, "Hello   World\n\tGPT  ");
		Product product = productJpaRepository.save(
			Product.create(UUID.randomUUID(), UUID.randomUUID(), content));

		Object fromDb = entityManager
			.createNativeQuery("""
				SELECT encode(sha256(convert_to(
				           lower(trim(regexp_replace(content, '[ \\t\\n\\r\\f\\v]+', ' ', 'g'))), 'UTF8')), 'hex')
				  FROM product WHERE id = :id
				""")
			.setParameter("id", product.getId())
			.getSingleResult();

		assertThat(fromDb.toString())
			.isEqualTo(ProductContentHash.of(content))
			.isEqualTo(product.getContentHash());
	}

	@Test
	@DisplayName("content_hash_at 컬럼이 만들어져 있다")
	void contentHashAtColumnExists() {
		Object count = entityManager
			.createNativeQuery("""
				SELECT count(*) FROM information_schema.columns
				 WHERE table_schema = 'product_service'
				   AND table_name = 'product'
				   AND column_name = 'content_hash_at'
				""")
			.getSingleResult();

		assertThat(((Number) count).intValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("해시가 실제로 바뀔 때만 content_hash_at이 갱신된다 — 무해 재편집은 순서를 밀지 않는다")
	void updatesContentHashAtOnlyWhenHashActuallyChanges() {
		UUID id = UUID.randomUUID();
		try {
			productJpaRepository.saveAndFlush(
				Product.create(id, UUID.randomUUID(), promptContent("원본", 1000, "본문")));
			commit();

			LocalDateTime firstHashAt = contentHashAt(id);
			assertThat(firstHashAt).isNotNull();

			beginNewTransaction();
			productJpaRepository.findById(id).orElseThrow()
				.update(promptContent("원본", 1000, "본문  "), null, false); // 정규화하면 동일 해시
			productJpaRepository.flush();
			commit();

			assertThat(contentHashAt(id)).isEqualTo(firstHashAt);

			beginNewTransaction();
			productJpaRepository.findById(id).orElseThrow()
				.update(promptContent("원본", 1000, "완전히 다른 본문"), null, false);
			productJpaRepository.flush();
			commit();

			assertThat(contentHashAt(id)).isAfter(firstHashAt);
		} finally {
			beginNewTransaction();
			entityManager.createNativeQuery("DELETE FROM product WHERE id = :id")
				.setParameter("id", id)
				.executeUpdate();
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
		entityManager.clear();
	}

	private LocalDateTime contentHashAt(UUID id) {
		Object result = entityManager
			.createNativeQuery("SELECT content_hash_at FROM product WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult();
		return (LocalDateTime) result;
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
