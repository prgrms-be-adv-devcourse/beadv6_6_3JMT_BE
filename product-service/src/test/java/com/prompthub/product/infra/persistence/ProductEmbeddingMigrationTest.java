package com.prompthub.product.infra.persistence;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V3 마이그레이션이 실제로 무엇을 만들었는지 검증한다.
 *
 * <p>{@code embedding}은 JPA에 매핑하지 않는다 — 도메인 로직이 벡터를 다루지 않고,
 * 쓰기는 전용 컴포넌트가, 읽기는 kNN 네이티브 쿼리가 담당한다. 그래서 여기서도
 * 네이티브 쿼리로 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryAdapter.class)
@ActiveProfiles("test")
class ProductEmbeddingMigrationTest extends PostgresIntegrationTestSupport {

	/** V3 마이그레이션의 vector(1536)과 같아야 한다. */
	private static final int DIMENSIONS = 1536;

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private ProductRepositoryAdapter productRepositoryAdapter;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("vector 확장이 설치돼 있다")
	void vectorExtensionInstalled() {
		Object count = entityManager
			.createNativeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
			.getSingleResult();

		assertThat(((Number) count).intValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("embedding 컬럼에 벡터를 저장하고 코사인 거리로 정렬해 조회한다")
	void storesAndOrdersByCosineDistance() {
		Product near = save(ProductStatus.ON_SALE);
		Product far = save(ProductStatus.ON_SALE);
		setEmbedding(near, 0);
		setEmbedding(far, 1);

		@SuppressWarnings("unchecked")
		List<UUID> ordered = entityManager
			.createNativeQuery("""
				SELECT id FROM product
				 WHERE embedding IS NOT NULL
				 ORDER BY embedding <=> (SELECT embedding FROM product WHERE id = :probeId)
				""")
			.setParameter("probeId", near.getId())
			.getResultList();

		assertThat(ordered).containsExactly(near.getId(), far.getId());
	}

	@Test
	@DisplayName("아주 작은 값도 저장된다 — Float.toString이 지수 표기(1.0E-5)를 낸다")
	void storesValuesInScientificNotation() {
		Product product = save(ProductStatus.ON_SALE);
		float[] embedding = new float[DIMENSIONS];
		// 임베딩 벡터는 이런 크기의 값을 실제로 포함한다. 리터럴이 "1.0E-5"로 나가는데
		// pgvector가 이걸 못 읽으면 캐스팅에서 터진다.
		embedding[0] = 0.00001f;

		productRepositoryAdapter.updateEmbedding(product.getId(), embedding, "해시");

		Object stored = entityManager
			.createNativeQuery("SELECT embedding IS NOT NULL FROM product WHERE id = :id")
			.setParameter("id", product.getId())
			.getSingleResult();
		assertThat((Boolean) stored).isTrue();
	}

	@Test
	@DisplayName("부분 HNSW 인덱스가 ON_SALE 조건으로 만들어져 있다")
	void partialHnswIndexExists() {
		Object indexDef = entityManager
			.createNativeQuery("""
				SELECT indexdef FROM pg_indexes
				 WHERE indexname = 'idx_product_embedding_on_sale'
				""")
			.getSingleResult();

		// 전 버전 행을 인덱싱하지 않는 것이 이 인덱스의 요점이라 WHERE 절까지 확인한다.
		assertThat(indexDef.toString())
			.contains("hnsw")
			.contains("vector_cosine_ops")
			.contains("ON_SALE");
	}

	private Product save(ProductStatus status) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return productJpaRepository.save(product);
	}

	/** 프로덕션 쓰기 경로를 그대로 쓴다 — 어댑터가 만드는 벡터 리터럴까지 함께 검증된다. */
	private void setEmbedding(Product product, int hotDimension) {
		float[] embedding = new float[DIMENSIONS];
		embedding[hotDimension] = 1f;
		productRepositoryAdapter.updateEmbedding(product.getId(), embedding, "해시-" + hotDimension);
	}
}
