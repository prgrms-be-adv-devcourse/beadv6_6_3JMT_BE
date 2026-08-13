package com.prompthub.search.infra.es.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.prompthub.recommendation.application.RecommendationCandidateQuery;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Seed;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway.RerankCandidate;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway.RankedProduct;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.indexing.ProductSearchDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 여러 추천 기준을 한 ES 요청으로 평가하고 Jina 하한을 통과한 후보만 돌려준다. */
@Component
@Slf4j
public class ElasticsearchRecommendationCandidateQuery implements RecommendationCandidateQuery {

	private static final int CANDIDATE_MULTIPLIER = 5;
	private static final int MAX_RERANK_CONCURRENCY = 4;
	private static final float MIN_SEMANTIC_SIMILARITY = 0.35f;
	private static final List<String> MATCH_FIELDS =
		List.of("name^3", "tags.text^2", "description^1.5", "model.text");

	private final ElasticsearchClient client;
	private final ProductRerankerGateway productRerankerGateway;

	public ElasticsearchRecommendationCandidateQuery(
		ElasticsearchClient client, ProductRerankerGateway productRerankerGateway
	) {
		this.client = client;
		this.productRerankerGateway = productRerankerGateway;
	}

	@Override
	public List<UUID> findRelevantProductIds(List<Seed> seeds, Set<UUID> excludedFamilyRootIds, int limit) {
		if (seeds.isEmpty() || limit <= 0) {
			return List.of();
		}

		int candidateLimit = limit * CANDIDATE_MULTIPLIER;
		SearchRequest request = buildRequest(seeds, excludedFamilyRootIds, candidateLimit);
		try {
			SearchResponse<ProductSearchDocument> response = client.search(request, ProductSearchDocument.class);
			List<ProductSearchDocument> candidates = response.hits().hits().stream()
				.map(hit -> hit.source())
				.filter(java.util.Objects::nonNull)
				.toList();
			return filterRelevantCandidates(seeds, candidates, limit);
		} catch (IOException | ElasticsearchException exception) {
			log.warn("ES 추천 후보 조회에 실패해 빈 추천을 반환합니다.", exception);
			return List.of();
		}
	}

	private SearchRequest buildRequest(List<Seed> seeds, Set<UUID> excludedFamilyRootIds, int candidateLimit) {
		List<Query> exclusions = exclusionFilters(excludedFamilyRootIds);
		Query lexicalQuery = Query.of(q -> q.bool(b -> b
			.should(seeds.stream().map(this::lexicalCondition).toList())
			.minimumShouldMatch("1")
			.mustNot(exclusions)));
		List<KnnSearch> vectorQueries = seeds.stream()
			.filter(seed -> seed.embedding() != null)
			.map(seed -> vectorCondition(seed, exclusions, candidateLimit))
			.toList();

		return SearchRequest.of(s -> s
			.index(ProductIndexBootstrap.ALIAS)
			.query(lexicalQuery)
			.knn(vectorQueries)
			.size(candidateLimit));
	}

	private Query lexicalCondition(Seed seed) {
		return Query.of(q -> q.multiMatch(m -> m
			.query(seed.text())
			.type(TextQueryType.BestFields)
			.tieBreaker(0.3)
			.fields(MATCH_FIELDS)
			.boost((float) seed.weight())));
	}

	private KnnSearch vectorCondition(Seed seed, List<Query> exclusions, int candidateLimit) {
		List<Float> vector = new ArrayList<>(seed.embedding().length);
		for (float value : seed.embedding()) {
			vector.add(value);
		}
		return KnnSearch.of(k -> k
			.field("embedding")
			.queryVector(vector)
			.k(candidateLimit)
			.numCandidates(candidateLimit * 2)
			.similarity(MIN_SEMANTIC_SIMILARITY)
			.filter(exclusions.stream().map(this::negate).toList())
			.boost((float) seed.weight()));
	}

	private List<Query> exclusionFilters(Set<UUID> excludedFamilyRootIds) {
		if (excludedFamilyRootIds.isEmpty()) {
			return List.of();
		}
		List<FieldValue> ids = excludedFamilyRootIds.stream()
			.map(UUID::toString)
			.map(FieldValue::of)
			.toList();
		return List.of(Query.of(q -> q.terms(t -> t.field("_id").terms(v -> v.value(ids)))));
	}

	private Query negate(Query query) {
		return Query.of(q -> q.bool(b -> b.mustNot(query)));
	}

	private List<UUID> filterRelevantCandidates(
		List<Seed> seeds, List<ProductSearchDocument> candidates, int limit
	) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		List<RerankCandidate> rerankCandidates = candidates.stream()
			.map(this::toRerankCandidate)
			.toList();
		if (seeds.size() == 1) {
			return productRerankerGateway.findRelevantProducts(seeds.getFirst().rerankText(), rerankCandidates)
				.map(ranked -> toProductIds(ranked, candidates, limit))
				.orElseGet(List::of);
		}
		return rankForMember(seeds, rerankCandidates, candidates, limit);
	}

	private List<UUID> rankForMember(
		List<Seed> seeds,
		List<RerankCandidate> rerankCandidates,
		List<ProductSearchDocument> candidates,
		int limit
	) {
		List<SeedRanking> rankings = new ArrayList<>(seeds.size());
		try (ExecutorService executor = Executors.newFixedThreadPool(
			Math.min(seeds.size(), MAX_RERANK_CONCURRENCY))) {
			List<CompletableFuture<Optional<List<RankedProduct>>>> futures = seeds.stream()
				.map(seed -> CompletableFuture.supplyAsync(
					() -> productRerankerGateway.findRelevantProducts(seed.rerankText(), rerankCandidates), executor))
				.toList();
			for (int index = 0; index < seeds.size(); index++) {
				Optional<List<RankedProduct>> ranked = futures.get(index).join();
				if (ranked.isEmpty()) {
					return List.of();
				}
				rankings.add(new SeedRanking(seeds.get(index), ranked.get()));
			}
		} catch (RuntimeException exception) {
			log.warn("회원 추천 재랭킹 집계에 실패해 빈 추천을 반환합니다.", exception);
			return List.of();
		}

		Map<UUID, Double> bestScores = new LinkedHashMap<>();
		for (SeedRanking ranking : rankings) {
			for (RankedProduct product : ranking.products()) {
				bestScores.merge(
					product.productId(), product.relevanceScore() * ranking.seed().weight(), Math::max);
			}
		}
		List<RankedProduct> rankedProducts = bestScores.entrySet().stream()
			.sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
			.map(entry -> new RankedProduct(entry.getKey(), entry.getValue()))
			.toList();
		return toProductIds(rankedProducts, candidates, limit);
	}

	private List<UUID> toProductIds(
		List<RankedProduct> rankedProducts, List<ProductSearchDocument> candidates, int limit
	) {
		Map<UUID, UUID> productIdsByFamily = candidates.stream().collect(java.util.stream.Collectors.toMap(
			ProductSearchDocument::familyRootId,
			ProductSearchDocument::productId,
			(existing, ignored) -> existing));
		return rankedProducts.stream()
			.map(RankedProduct::productId)
			.map(productIdsByFamily::get)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.limit(limit)
			.toList();
	}

	private record SeedRanking(Seed seed, List<RankedProduct> products) {
	}

	private RerankCandidate toRerankCandidate(ProductSearchDocument document) {
		return new RerankCandidate(
			document.familyRootId(), document.name(), document.tags(), document.description(),
			document.productType(), document.model(), false);
	}
}
