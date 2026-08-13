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
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.indexing.ProductSearchDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 여러 추천 기준을 한 ES 요청으로 평가하고 Jina 하한을 통과한 후보만 돌려준다. */
@Component
@Slf4j
public class ElasticsearchRecommendationCandidateQuery implements RecommendationCandidateQuery {

	private static final int CANDIDATE_MULTIPLIER = 5;
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
		Optional<List<UUID>> relevantIds = productRerankerGateway.findRelevantProductIds(
			buildRerankQuery(seeds), rerankCandidates);
		if (relevantIds.isEmpty()) {
			return List.of();
		}

		Set<UUID> relevant = new HashSet<>(relevantIds.get());
		return candidates.stream()
			.filter(candidate -> relevant.contains(candidate.familyRootId()))
			.map(ProductSearchDocument::productId)
			.limit(limit)
			.toList();
	}

	private String buildRerankQuery(List<Seed> seeds) {
		return seeds.stream()
			.map(seed -> seed.signal().name() + "\n" + seed.rerankText())
			.collect(java.util.stream.Collectors.joining("\n\n"));
	}

	private RerankCandidate toRerankCandidate(ProductSearchDocument document) {
		return new RerankCandidate(
			document.familyRootId(), document.name(), document.tags(), document.description(),
			document.productType(), document.model(), false);
	}
}
