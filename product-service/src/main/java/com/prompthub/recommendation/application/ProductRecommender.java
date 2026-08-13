package com.prompthub.recommendation.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Seed;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Signal;
import com.prompthub.search.application.embedding.EmbeddingSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 상품 상세와 회원 활동에서 추천 기준을 만들고 노출 제외 정책을 적용한다. */
@Component
public class ProductRecommender {

	private static final int MAX_SEEDS_PER_SIGNAL = 5;
	private static final double CART_WEIGHT = 1.0;
	private static final double PURCHASE_WEIGHT = 0.7;

	private final ProductRepository productRepository;
	private final RecommendationCandidateQuery candidateQuery;

	public ProductRecommender(ProductRepository productRepository, RecommendationCandidateQuery candidateQuery) {
		this.productRepository = productRepository;
		this.candidateQuery = candidateQuery;
	}

	public List<UUID> recommendSimilar(Product product, int limit) {
		Map<UUID, float[]> embeddings = productRepository.findEmbeddings(List.of(product.getId()));
		Seed seed = seedOf(product, embeddings.get(product.getId()), 1.0, Signal.SIMILAR_PRODUCT);
		return candidateQuery.findRelevantProductIds(List.of(seed), Set.of(product.familyRootId()), limit);
	}

	public List<UUID> recommendForActivity(
		List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit
	) {
		List<UUID> allActivityIds = allDistinctIds(cartProductIds, purchasedProductIds);
		if (allActivityIds.isEmpty()) {
			return List.of();
		}
		List<UUID> cartIds = recentDistinctIds(cartProductIds);
		List<UUID> purchaseIds = recentDistinctIds(purchasedProductIds);
		List<UUID> activityIds = new ArrayList<>(cartIds);
		purchaseIds.stream().filter(id -> !activityIds.contains(id)).forEach(activityIds::add);

		Map<UUID, Product> productsById = productRepository.findAllByIdIn(allActivityIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		Map<UUID, float[]> embeddings = productRepository.findEmbeddings(activityIds);
		List<Seed> seeds = new ArrayList<>();
		addSeeds(seeds, cartIds, productsById, embeddings, CART_WEIGHT, Signal.CART);
		addSeeds(seeds, purchaseIds, productsById, embeddings, PURCHASE_WEIGHT, Signal.PURCHASE);
		if (seeds.isEmpty()) {
			return List.of();
		}

		Set<UUID> excludedFamilies = productsById.values().stream()
			.map(Product::familyRootId)
			.collect(Collectors.toSet());
		return candidateQuery.findRelevantProductIds(seeds, excludedFamilies, limit);
	}

	private List<UUID> allDistinctIds(List<UUID> cartProductIds, List<UUID> purchasedProductIds) {
		List<UUID> allIds = new ArrayList<>(cartProductIds != null ? cartProductIds : List.of());
		if (purchasedProductIds != null) {
			allIds.addAll(purchasedProductIds);
		}
		return allIds.stream().distinct().toList();
	}

	private List<UUID> recentDistinctIds(List<UUID> productIds) {
		if (productIds == null) {
			return List.of();
		}
		return productIds.stream()
			.distinct()
			.limit(MAX_SEEDS_PER_SIGNAL)
			.toList();
	}

	private void addSeeds(
		List<Seed> seeds,
		List<UUID> productIds,
		Map<UUID, Product> productsById,
		Map<UUID, float[]> embeddings,
		double weight,
		Signal signal
	) {
		Set<UUID> addedIds = seeds.stream().map(Seed::productId).collect(Collectors.toSet());
		for (UUID productId : productIds) {
			Product product = productsById.get(productId);
			if (product == null || addedIds.contains(productId)) {
				continue;
			}
			seeds.add(seedOf(product, embeddings.get(productId), weight, signal));
			addedIds.add(productId);
		}
	}

	private Seed seedOf(Product product, float[] embedding, double weight, Signal signal) {
		return new Seed(product.getId(), EmbeddingSource.of(product).text(), embedding, weight, signal);
	}
}
