package com.prompthub.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Seed;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductRecommenderTest {

	private static final UUID CART = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PURCHASED = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID RECOMMENDED = UUID.fromString("30000000-0000-0000-0000-000000000001");

	@Mock
	private ProductRepository productRepository;

	private RecordingCandidateQuery candidateQuery;
	private ProductRecommender recommender;

	@BeforeEach
	void setUp() {
		candidateQuery = new RecordingCandidateQuery();
		recommender = new ProductRecommender(productRepository, candidateQuery);
	}

	@Test
	@DisplayName("비슷한 상품은 현재 상품 하나와 자기 상품군 제외 조건으로 조회한다")
	void recommendsSimilarProductsFromOneSeed() {
		Product base = product(CART, CART, "코드 리뷰", ProductType.PROMPT, "GPT-5");
		given(productRepository.findEmbeddings(List.of(CART))).willReturn(Map.of(CART, new float[]{1f}));
		candidateQuery.result = List.of(RECOMMENDED);

		assertThat(recommender.recommendSimilar(base, 4)).containsExactly(RECOMMENDED);
		assertThat(candidateQuery.seeds).singleElement().satisfies(seed -> {
			assertThat(seed.productId()).isEqualTo(CART);
			assertThat(seed.signal()).isEqualTo(Signal.SIMILAR_PRODUCT);
			assertThat(seed.text()).contains("코드 리뷰", "GPT-5");
			assertThat(seed.text()).doesNotContain("본문 예시");
			assertThat(seed.rerankText()).contains("코드 리뷰", "태그");
			assertThat(seed.rerankText()).doesNotContain("설명", "GPT-5", "본문 예시");
		});
		assertThat(candidateQuery.excludedFamilies).containsExactly(CART);
	}

	@Test
	@DisplayName("회원 추천은 장바구니와 구매를 최신 5개씩만 사용하고 한 번에 조회한다")
	void recommendsFromAtMostFiveSeedsPerSignal() {
		List<UUID> cartIds = ids("40000000", 6);
		List<UUID> purchaseIds = ids("50000000", 6);
		List<UUID> allActivityIds = new ArrayList<>(cartIds);
		allActivityIds.addAll(purchaseIds);
		List<UUID> selectedIds = new ArrayList<>(cartIds.subList(0, 5));
		selectedIds.addAll(purchaseIds.subList(0, 5));
		List<Product> products = allActivityIds.stream()
			.map(id -> product(id, id, "상품 " + id, ProductType.PPT, null))
			.toList();
		given(productRepository.findAllByIdIn(allActivityIds)).willReturn(products);
		given(productRepository.findEmbeddings(selectedIds)).willReturn(Map.of());

		recommender.recommendForActivity(cartIds, purchaseIds, 4);

		assertThat(candidateQuery.calls).isEqualTo(1);
		assertThat(candidateQuery.seeds).hasSize(10);
		assertThat(candidateQuery.seeds.subList(0, 5)).allMatch(seed -> seed.signal() == Signal.CART);
		assertThat(candidateQuery.seeds.subList(5, 10)).allMatch(seed -> seed.signal() == Signal.PURCHASE);
		assertThat(candidateQuery.seeds.subList(0, 5)).allMatch(seed -> seed.weight() == 1.0);
		assertThat(candidateQuery.seeds.subList(5, 10)).allMatch(seed -> seed.weight() == 0.7);
		assertThat(candidateQuery.excludedFamilies).containsExactlyInAnyOrderElementsOf(allActivityIds);
	}

	@Test
	@DisplayName("활동 상품이 없으면 후보 조회를 하지 않는다")
	void noActivityReturnsEmptyWithoutCandidateQuery() {
		assertThat(recommender.recommendForActivity(List.of(), null, 4)).isEmpty();
		assertThat(candidateQuery.calls).isZero();
	}

	private Product product(UUID id, UUID familyRootId, String name, ProductType type, String model) {
		Product product = org.mockito.Mockito.mock(Product.class);
		given(product.getId()).willReturn(id);
		given(product.familyRootId()).willReturn(familyRootId);
		lenient().when(product.getName()).thenReturn(name);
		lenient().when(product.getDescription()).thenReturn("설명");
		lenient().when(product.getContent()).thenReturn("본문 예시");
		lenient().when(product.getTags()).thenReturn(List.of("태그"));
		lenient().when(product.getProductType()).thenReturn(type);
		if (type == ProductType.PROMPT) {
			lenient().when(product.getModel()).thenReturn(model);
		}
		return product;
	}

	private List<UUID> ids(String prefix, int count) {
		List<UUID> ids = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			ids.add(UUID.fromString(prefix + "-0000-0000-0000-" + String.format("%012d", index)));
		}
		return ids;
	}

	private static class RecordingCandidateQuery implements RecommendationCandidateQuery {
		private int calls;
		private List<Seed> seeds = List.of();
		private Set<UUID> excludedFamilies = Set.of();
		private List<UUID> result = List.of();

		@Override
		public List<UUID> findRelevantProductIds(List<Seed> seeds, Set<UUID> excludedFamilyRootIds, int limit) {
			calls++;
			this.seeds = seeds;
			this.excludedFamilies = excludedFamilyRootIds;
			return result;
		}
	}
}
