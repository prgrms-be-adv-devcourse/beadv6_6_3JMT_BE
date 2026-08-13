package com.prompthub.search.infra.es.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import com.prompthub.search.application.embedding.QueryEmbeddingCache;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway;
import com.prompthub.search.application.query.ProductSearchUnavailableException;
import com.prompthub.search.infra.es.indexing.ProductSearchDocument;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ElasticsearchProductSearchQuerierTest {

	@Mock
	private ElasticsearchClient client;

	@Mock
	private ProductSearchQueryBuilder queryBuilder;

	@Mock
	private QueryEmbeddingCache queryEmbeddingCache;

	@Mock
	private ProductRerankerGateway productRerankerGateway;

	private ElasticsearchProductSearchQuerier querier;

	@BeforeEach
	void setUp() {
		querier = new ElasticsearchProductSearchQuerier(client, queryBuilder, queryEmbeddingCache, productRerankerGateway);
	}

	@Test
	@DisplayName("ES 통신 실패는 검색 unavailable 예외로 변환한다")
	void search_convertsIoFailureToUnavailableException() throws IOException {
		SearchRequest request = mock(SearchRequest.class);
		given(queryBuilder.build("", "all", "popular", PageRequest.of(0, 20))).willReturn(request);
		given(client.search(request, ProductSearchDocument.class)).willThrow(new IOException("connection refused"));

		assertThatThrownBy(() -> querier.search("", "all", "popular", PageRequest.of(0, 20)))
			.isInstanceOf(ProductSearchUnavailableException.class)
			.hasCauseInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("ES 응답 조립 결함은 unavailable 예외로 숨기지 않는다")
	void search_doesNotHideResponseMappingFailure() throws IOException {
		SearchRequest request = mock(SearchRequest.class);
		given(queryBuilder.build("", "all", "popular", PageRequest.of(0, 20))).willReturn(request);
		given(client.search(request, ProductSearchDocument.class)).willReturn(null);

		assertThatThrownBy(() -> querier.search("", "all", "popular", PageRequest.of(0, 20)))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("msearch item failure는 검색 unavailable 예외로 변환한다")
	void hybridSearch_convertsItemFailureToUnavailableException() throws IOException {
		SearchRequest lexical = mock(SearchRequest.class, RETURNS_DEEP_STUBS);
		SearchRequest semantic = mock(SearchRequest.class, RETURNS_DEEP_STUBS);
		given(queryEmbeddingCache.get("hybrid")).willReturn(new float[]{0.1f});
		given(queryBuilder.build("hybrid", "all", "popular", 0, 50)).willReturn(lexical);
		given(queryBuilder.buildKnn(any(float[].class), eq("all"), eq(50))).willReturn(semantic);

		@SuppressWarnings("unchecked")
		MultiSearchResponseItem<ProductSearchDocument> failedItem =
			(MultiSearchResponseItem<ProductSearchDocument>) mock(MultiSearchResponseItem.class, RETURNS_DEEP_STUBS);
		given(failedItem.isFailure()).willReturn(true);
		given(failedItem.failure().error().reason()).willReturn("shard failed");
		@SuppressWarnings("unchecked")
		MsearchResponse<ProductSearchDocument> response =
			(MsearchResponse<ProductSearchDocument>) mock(MsearchResponse.class);
		given(response.responses()).willReturn(List.of(failedItem, failedItem));
		given(client.msearch(any(MsearchRequest.class), eq(ProductSearchDocument.class))).willReturn(response);

		assertThatThrownBy(() -> querier.search("hybrid", "all", "popular", PageRequest.of(0, 20)))
			.isInstanceOf(ProductSearchUnavailableException.class)
			.hasMessageContaining("레그 조회");
	}

	@Test
	@DisplayName("hybrid ES 통신 실패는 검색 unavailable 예외로 변환한다")
	void hybridSearch_convertsIoFailureToUnavailableException() throws IOException {
		SearchRequest lexical = mock(SearchRequest.class, RETURNS_DEEP_STUBS);
		SearchRequest semantic = mock(SearchRequest.class, RETURNS_DEEP_STUBS);
		given(queryEmbeddingCache.get("hybrid")).willReturn(new float[]{0.1f});
		given(queryBuilder.build("hybrid", "all", "popular", 0, 50)).willReturn(lexical);
		given(queryBuilder.buildKnn(any(float[].class), eq("all"), eq(50))).willReturn(semantic);
		given(client.msearch(any(MsearchRequest.class), eq(ProductSearchDocument.class)))
			.willThrow(new IOException("connection refused"));

		assertThatThrownBy(() -> querier.search("hybrid", "all", "popular", PageRequest.of(0, 20)))
			.isInstanceOf(ProductSearchUnavailableException.class)
			.hasCauseInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("suggest ES 통신 실패는 검색 unavailable 예외로 변환한다")
	void suggest_convertsIoFailureToUnavailableException() throws IOException {
		SearchRequest request = mock(SearchRequest.class);
		given(queryBuilder.buildSuggest("프롬", 5)).willReturn(request);
		given(client.search(request, ProductSearchDocument.class)).willThrow(new IOException("connection refused"));

		assertThatThrownBy(() -> querier.suggest("프롬", 5))
			.isInstanceOf(ProductSearchUnavailableException.class)
			.hasCauseInstanceOf(IOException.class);
	}
}
