package com.prompthub.search.infra.es.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Seed;
import com.prompthub.recommendation.application.RecommendationCandidateQuery.Signal;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway.RankedProduct;
import com.prompthub.search.infra.es.indexing.ProductSearchDocument;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ElasticsearchRecommendationCandidateQueryTest {

	private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID FIRST_PRODUCT = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID SECOND_PRODUCT = UUID.fromString("20000000-0000-0000-0000-000000000002");

	@Test
	@DisplayName("회원 추천은 seed별 Jina 점수에 가중치를 적용하고 후보별 최고점 순서로 반환한다")
	void ranksCandidatesByBestWeightedSeedScore() throws IOException {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ProductRerankerGateway reranker = mock(ProductRerankerGateway.class);
		SearchResponse<ProductSearchDocument> response = response(document(FIRST), document(SECOND));
		given(client.search(any(SearchRequest.class), eq(ProductSearchDocument.class))).willReturn(response);
		given(reranker.findRelevantProducts(any(), any()))
			.willAnswer(invocation -> invocation.<String>getArgument(0).contains("장바구니")
				? Optional.of(List.of(new RankedProduct(SECOND, 0.5), new RankedProduct(FIRST, 0.4)))
				: Optional.of(List.of(new RankedProduct(FIRST, 0.4), new RankedProduct(SECOND, 0.1))));
		ElasticsearchRecommendationCandidateQuery query =
			new ElasticsearchRecommendationCandidateQuery(client, reranker);

		List<UUID> result = query.findRelevantProductIds(
			List.of(
				new Seed(UUID.randomUUID(), "장바구니 설명", "장바구니 의도", new float[]{1f}, 1.0, Signal.CART),
				new Seed(UUID.randomUUID(), "구매 설명", "구매 의도", new float[]{0f, 1f}, 0.7, Signal.PURCHASE)),
			Set.of(UUID.randomUUID()),
			4);

		assertThat(result).containsExactly(SECOND_PRODUCT, FIRST_PRODUCT);
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(client).search(requestCaptor.capture(), eq(ProductSearchDocument.class));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ProductRerankerGateway.RerankCandidate>> candidatesCaptor =
			ArgumentCaptor.forClass(List.class);
		verify(reranker, times(2)).findRelevantProducts(any(), candidatesCaptor.capture());
		assertThat(candidatesCaptor.getAllValues()).allSatisfy(
			candidates -> assertThat(candidates).allMatch(candidate -> !candidate.includeModel()));
		SearchRequest request = requestCaptor.getValue();
		assertThat(request.size()).isEqualTo(20);
		assertThat(request.query().bool().should()).hasSize(2);
		assertThat(request.query().bool().mustNot()).hasSize(1);
		assertThat(request.knn()).hasSize(2);
		assertThat(request.knn()).extracting(vectorQuery -> vectorQuery.boost()).containsExactly(1.0f, 0.7f);
	}

	@Test
	@DisplayName("ES 후보 조회에 실패하면 검증되지 않은 추천을 노출하지 않는다")
	void returnsEmptyWhenElasticsearchIsUnavailable() throws IOException {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ProductRerankerGateway reranker = mock(ProductRerankerGateway.class);
		given(client.search(any(SearchRequest.class), eq(ProductSearchDocument.class)))
			.willThrow(new IOException("unavailable"));

		assertThat(new ElasticsearchRecommendationCandidateQuery(client, reranker)
			.findRelevantProductIds(
				List.of(new Seed(UUID.randomUUID(), "기준 설명", "기준", null, 1.0, Signal.SIMILAR_PRODUCT)),
				Set.of(),
				4))
			.isEmpty();
	}

	@Test
	@DisplayName("Jina를 사용할 수 없으면 ES 후보를 노출하지 않는다")
	void returnsEmptyWhenRerankerIsUnavailable() throws IOException {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ProductRerankerGateway reranker = mock(ProductRerankerGateway.class);
		SearchResponse<ProductSearchDocument> response = response(document(FIRST));
		given(client.search(any(SearchRequest.class), eq(ProductSearchDocument.class)))
			.willReturn(response);
		given(reranker.findRelevantProducts(any(), any())).willReturn(Optional.empty());

		assertThat(new ElasticsearchRecommendationCandidateQuery(client, reranker)
			.findRelevantProductIds(
				List.of(new Seed(UUID.randomUUID(), "기준 설명", "기준", null, 1.0, Signal.SIMILAR_PRODUCT)),
				Set.of(),
				4))
			.isEmpty();
	}

	@Test
	@DisplayName("Jina 하한을 통과한 후보가 없으면 빈 추천을 반환한다")
	void returnsEmptyWhenEveryCandidateIsIrrelevant() throws IOException {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ProductRerankerGateway reranker = mock(ProductRerankerGateway.class);
		SearchResponse<ProductSearchDocument> response = response(document(FIRST));
		given(client.search(any(SearchRequest.class), eq(ProductSearchDocument.class)))
			.willReturn(response);
		given(reranker.findRelevantProducts(any(), any())).willReturn(Optional.of(List.of()));

		assertThat(new ElasticsearchRecommendationCandidateQuery(client, reranker)
			.findRelevantProductIds(
				List.of(new Seed(UUID.randomUUID(), "기준 설명", "기준", null, 1.0, Signal.SIMILAR_PRODUCT)),
				Set.of(),
				4))
			.isEmpty();
	}

	@SuppressWarnings("unchecked")
	private SearchResponse<ProductSearchDocument> response(ProductSearchDocument... documents) {
		SearchResponse<ProductSearchDocument> response = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
		List<Hit<ProductSearchDocument>> hits = java.util.Arrays.stream(documents)
			.map(document -> {
				Hit<ProductSearchDocument> hit = mock(Hit.class);
				given(hit.source()).willReturn(document);
				return hit;
			})
			.toList();
		given(response.hits().hits()).willReturn(hits);
		return response;
	}

	private ProductSearchDocument document(UUID id) {
		UUID productId = id.equals(FIRST) ? FIRST_PRODUCT : SECOND_PRODUCT;
		return new ProductSearchDocument(
			id, productId, UUID.randomUUID(), "상품", "설명", null, List.of("태그"), "PROMPT", "GPT-5",
			1000, "PAID", null, 0, 0, 0, 0.0, null, null, new float[]{1f});
	}
}
