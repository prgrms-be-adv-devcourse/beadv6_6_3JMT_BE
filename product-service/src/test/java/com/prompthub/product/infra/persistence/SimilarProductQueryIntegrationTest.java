package com.prompthub.product.infra.persistence;
import com.prompthub.product.infra.persistence.query.ProductJpaRepository;
import com.prompthub.product.infra.persistence.query.ProductRepositoryAdapter;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.projection.SimilarProductProjection;
import com.prompthub.product.domain.model.vo.ProductContent;
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
 * kNN 후보 조회를 실제 pgvector 위에서 검증한다.
 *
 * <p>제외 조건(자기 family·비ON_SALE·임베딩 없음)이 전부 SQL 안에 있어서, 쿼리를 돌려보지
 * 않으면 맞는지 확인할 방법이 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryAdapter.class)
@ActiveProfiles("test")
class SimilarProductQueryIntegrationTest extends PostgresIntegrationTestSupport {

	private static final int DIMENSIONS = 1536;

	@Autowired
	private ProductRepositoryAdapter productRepository;

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("임베딩이 가까운 상품이 먼 상품보다 앞에 온다")
	void ordersByDistance() {
		Product base = save(promptContent("기준상품", 1000), 0);
		Product near = save(promptContent("가까운상품", 1000), 0);
		Product far = save(promptContent("먼상품", 1000), 1);

		List<UUID> found = idsOf(base, 10);

		assertThat(found).containsExactly(near.getId(), far.getId());
	}

	@Test
	@DisplayName("자기 family의 다른 버전은 결과에 없다")
	void excludesOwnFamily() {
		Product base = save(promptContent("기준상품", 1000), 0);
		Product sibling = save(promptContent("같은family 다른버전", 1000), 0);
		ReflectionTestUtils.setField(sibling, "parentId", base.getId());
		ReflectionTestUtils.setField(sibling, "majorVersion", (short) 2); // 같은 family에 다른 version(V10 unique 대상)
		productJpaRepository.saveAndFlush(sibling);

		assertThat(idsOf(base, 10)).doesNotContain(sibling.getId());
	}

	@Test
	@DisplayName("판매 중이 아닌 상품은 결과에 없다")
	void excludesNotOnSale() {
		Product base = save(promptContent("기준상품", 1000), 0);
		Product draft = save(promptContent("임시저장상품", 1000), 0);
		ReflectionTestUtils.setField(draft, "status", ProductStatus.DRAFT);
		productJpaRepository.saveAndFlush(draft);

		assertThat(idsOf(base, 10)).doesNotContain(draft.getId());
	}

	@Test
	@DisplayName("임베딩이 아직 없는 상품은 결과에 없다")
	void excludesMissingEmbedding() {
		Product base = save(promptContent("기준상품", 1000), 0);
		Product notEmbedded = productJpaRepository.saveAndFlush(onSale(promptContent("임베딩없음", 1000)));

		assertThat(idsOf(base, 10)).doesNotContain(notEmbedded.getId());
	}

	@Test
	@DisplayName("기준 상품에 임베딩이 아직 없으면 빈 결과다")
	void returnsEmptyWhenBaseHasNoEmbedding() {
		// 기준 상품의 임베딩이 없으면 서브쿼리가 NULL이고 `<=> NULL`도 NULL이라, 가드가 없으면
		// 후보 행이 distance=NULL로 돌아와 primitive double 매핑에서 NPE가 난다.
		Product base = productJpaRepository.saveAndFlush(onSale(promptContent("임베딩없는기준상품", 1000)));
		save(promptContent("후보상품", 1000), 0);

		assertThat(productRepository.findSimilarProducts(base.getId(), base.familyRootId(), 10)).isEmpty();
	}

	@Test
	@DisplayName("다른 유형도 후보에 포함된다 — 유형 판단은 SQL이 아니라 재정렬의 몫이다")
	void includesOtherTypes() {
		Product base = save(promptContent("기준상품", 1000), 0);
		Product notion = save(notionContent("노션상품", 1000), 0);

		List<SimilarProductProjection> found =
			productRepository.findSimilarProducts(base.getId(), base.familyRootId(), 10);

		assertThat(found).extracting(SimilarProductProjection::id).contains(notion.getId());
		assertThat(found).extracting(SimilarProductProjection::productType).contains("NOTION");
	}

	@Test
	@DisplayName("요청한 후보 수까지만 돌려준다")
	void respectsCandidateLimit() {
		Product base = save(promptContent("기준상품", 1000), 0);
		for (int i = 0; i < 5; i++) {
			save(promptContent("후보" + i, 1000), 0);
		}

		assertThat(productRepository.findSimilarProducts(base.getId(), base.familyRootId(), 3)).hasSize(3);
	}

	@Test
	@DisplayName("정렬이 HNSW 인덱스를 탄다")
	void usesHnswIndex() {
		Product base = save(promptContent("기준상품", 1000), 0);

		// 정렬식에 산술을 얹으면(예: 유형 가산점) 표현식이 되어 인덱스를 놓친다. 지금 규모에선
		// 안 보이다가 상품이 늘면 조용히 느려지므로 실행 계획을 직접 확인한다.
		@SuppressWarnings("unchecked")
		List<Object> plan = entityManager
			.createNativeQuery("""
				EXPLAIN
				select p.id from product p
				 where p.status = 'ON_SALE' and p.deleted_at is null and p.embedding is not null
				 order by p.embedding <=> (select embedding from product where id = :productId)
				 limit 10
				""")
			.setParameter("productId", base.getId())
			.getResultList();

		assertThat(plan.stream().map(String::valueOf).reduce("", String::concat))
			.contains("idx_product_embedding_on_sale");
	}

	@Test
	@DisplayName("한 가족의 여러 ON_SALE 버전은 후보에 1건만 온다")
	void collapsesCandidateFamilies() {
		// #699 — 승인 시 supersede 누락으로 한 가족에 ON_SALE이 2행 쌓이면, 버전끼리 임베딩이
		// 거의 같아 나란히 후보에 들어와 추천 카드가 중복된다. 가족당 최근접 1건만 남아야 한다.
		Product base = save(promptContent("기준상품", 1000), 0);
		Product familyRoot = save(promptContent("후보 v1", 1000), 0);
		Product newerVersion = onSale(promptContent("후보 v2", 1000));
		ReflectionTestUtils.setField(newerVersion, "parentId", familyRoot.getId());
		ReflectionTestUtils.setField(newerVersion, "majorVersion", (short) 2); // v1과 구분(V10 unique 대상)
		productJpaRepository.saveAndFlush(newerVersion);
		float[] embedding = new float[DIMENSIONS];
		embedding[0] = 1f;
		productRepository.updateEmbedding(newerVersion.getId(), embedding, "해시-" + newerVersion.getId());

		List<UUID> found = idsOf(base, 10);

		long familyHits = found.stream()
			.filter(id -> id.equals(familyRoot.getId()) || id.equals(newerVersion.getId()))
			.count();
		assertThat(familyHits).isEqualTo(1);
	}

	private List<UUID> idsOf(Product base, int candidates) {
		return productRepository.findSimilarProducts(base.getId(), base.familyRootId(), candidates).stream()
			.map(SimilarProductProjection::id)
			.toList();
	}

	/** hotDimension만 1인 단위 벡터 — 축이 다르면 코사인 거리가 최대라 순서가 뚜렷하다. */
	private Product save(ProductContent content, int hotDimension) {
		Product product = productJpaRepository.saveAndFlush(onSale(content));
		float[] embedding = new float[DIMENSIONS];
		embedding[hotDimension] = 1f;
		productRepository.updateEmbedding(product.getId(), embedding, "해시-" + product.getId());
		return product;
	}

	private Product onSale(ProductContent content) {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), content);
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
		return product;
	}
}
