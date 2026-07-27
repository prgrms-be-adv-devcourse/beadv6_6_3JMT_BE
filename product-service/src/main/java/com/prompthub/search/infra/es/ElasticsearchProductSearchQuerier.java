package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import com.prompthub.search.application.ProductSearchHit;
import com.prompthub.search.application.ProductSearchPageResult;
import com.prompthub.search.application.ProductSearchQueryService;
import com.prompthub.search.application.QueryEmbeddingCache;
import com.prompthub.search.application.ReciprocalRankFusion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchQuerier implements ProductSearchQueryService {

	/**
	 * 하이브리드는 이 정렬에서만 돈다.
	 *
	 * <p>{@code rating}·{@code price-asc}는 값 기준 정렬이라 순서가 그 필드로 완전히 결정된다.
	 * 두 레그를 섞어도 정렬이 덮어써서 병합이 무의미하고 외부 API 호출만 낭비된다.
	 */
	private static final String HYBRID_SORT = ProductSearchQueryBuilder.SORT_POPULAR;

	/**
	 * 두 레그에서 각각 가져와 병합할 문서 수.
	 *
	 * <p>RRF는 순위 목록을 통째로 섞는 방식이라 페이지 단위로 나눠 계산할 수 없다.
	 * 이 창을 넘어가는 페이지는 글자 기반 결과만 준다 — 현재 상품 수(65)에서는 전체가 창에
	 * 들어오고, 검색 결과 100건 뒤까지 넘겨보는 사용은 사실상 없다.
	 */
	private static final int FUSION_WINDOW = 100;

	private final ElasticsearchClient client;
	private final ProductSearchQueryBuilder queryBuilder;
	private final QueryEmbeddingCache queryEmbeddingCache;

	@Override
	public ProductSearchPageResult search(String keyword, String productType, String sort, Pageable pageable) {
		if (shouldTryHybrid(keyword, sort, pageable)) {
			float[] queryVector = queryEmbeddingCache.get(keyword);
			if (queryVector != null) {
				return hybridSearch(keyword, productType, sort, pageable, queryVector);
			}
		}
		return lexicalSearch(queryBuilder.build(keyword, productType, sort, pageable));
	}

	private boolean shouldTryHybrid(String keyword, String sort, Pageable pageable) {
		return keyword != null
			&& !keyword.isBlank()
			&& HYBRID_SORT.equals(sort)
			&& pageable.getOffset() + pageable.getPageSize() <= FUSION_WINDOW;
	}

	private ProductSearchPageResult lexicalSearch(SearchRequest request) {
		try {
			SearchResponse<ProductSearchDocument> response = client.search(request, ProductSearchDocument.class);
			List<ProductSearchHit> hits = response.hits().hits().stream()
				.map(hit -> toHit(hit.source()))
				.toList();
			return new ProductSearchPageResult(hits, totalOf(response, hits.size()));
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 검색에 실패했습니다.", e);
		}
	}

	/**
	 * 글자 기반과 의미 기반을 한 번의 왕복으로 동시에 조회해 순위를 병합한다.
	 *
	 * <p>총 건수는 <b>글자 기반 레그의 값을 그대로 쓴다.</b> 의미 기반은 상위 k건만 돌려주므로
	 * 전체 건수를 알 수 없고, 호출자가 이 값으로 다음 페이지 존재 여부를 계산한다.
	 */
	private ProductSearchPageResult hybridSearch(
		String keyword, String productType, String sort, Pageable pageable, float[] queryVector
	) {
		SearchRequest lexical = queryBuilder.build(keyword, productType, sort, 0, FUSION_WINDOW);
		SearchRequest semantic = queryBuilder.buildKnn(queryVector, productType, FUSION_WINDOW);

		try {
			MsearchResponse<ProductSearchDocument> response = client.msearch(
				MsearchRequest.of(m -> m.searches(
					RequestItem.of(r -> r.header(h -> h.index(ProductIndexBootstrap.ALIAS)).body(b -> b
						.query(lexical.query())
						.sort(lexical.sort())
						.from(lexical.from())
						.size(lexical.size())
						.trackTotalHits(t -> t.enabled(true)))),
					RequestItem.of(r -> r.header(h -> h.index(ProductIndexBootstrap.ALIAS)).body(b -> b
						.knn(semantic.knn())
						.size(semantic.size()))))),
				ProductSearchDocument.class);

			List<MultiSearchResponseItem<ProductSearchDocument>> items = response.responses();
			MultiSearchItem<ProductSearchDocument> lexicalResult = resultOf(items, 0);
			MultiSearchItem<ProductSearchDocument> semanticResult = resultOf(items, 1);

			Map<UUID, ProductSearchDocument> documents = new LinkedHashMap<>();
			List<UUID> lexicalRanking = collect(lexicalResult, documents);
			List<UUID> semanticRanking = collect(semanticResult, documents);

			List<UUID> fused = ReciprocalRankFusion.fuse(lexicalRanking, semanticRanking);
			List<ProductSearchHit> page = slice(fused, pageable).stream()
				.map(documents::get)
				.filter(Objects::nonNull)
				.map(this::toHit)
				.toList();

			return new ProductSearchPageResult(page, totalOf(lexicalResult, fused.size()));
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 하이브리드 검색에 실패했습니다.", e);
		}
	}

	private MultiSearchItem<ProductSearchDocument> resultOf(
		List<MultiSearchResponseItem<ProductSearchDocument>> items, int index
	) {
		MultiSearchResponseItem<ProductSearchDocument> item = items.get(index);
		if (item.isFailure()) {
			throw new IllegalStateException("ES 레그 조회가 실패했습니다. reason=" + item.failure().error().reason());
		}
		return item.result();
	}

	/** 응답의 문서를 순위 목록으로 뽑으면서 병합 후 다시 꺼내 쓸 수 있게 맵에도 담는다. */
	private List<UUID> collect(
		ResponseBody<ProductSearchDocument> response, Map<UUID, ProductSearchDocument> documents
	) {
		List<UUID> ranking = new ArrayList<>();
		for (var hit : response.hits().hits()) {
			ProductSearchDocument document = hit.source();
			if (document == null || document.familyRootId() == null) {
				continue;
			}
			documents.putIfAbsent(document.familyRootId(), document);
			ranking.add(document.familyRootId());
		}
		return ranking;
	}

	private List<UUID> slice(List<UUID> fused, Pageable pageable) {
		int from = (int) pageable.getOffset();
		if (from >= fused.size()) {
			return List.of();
		}
		return fused.subList(from, Math.min(from + pageable.getPageSize(), fused.size()));
	}

	private long totalOf(ResponseBody<ProductSearchDocument> response, long fallback) {
		return response.hits().total() != null ? response.hits().total().value() : fallback;
	}

	@Override
	public List<String> suggest(String keyword, int limit) {
		SearchRequest request = queryBuilder.buildSuggest(keyword, limit);
		try {
			SearchResponse<ProductSearchDocument> response = client.search(request, ProductSearchDocument.class);
			return response.hits().hits().stream()
				.map(hit -> hit.source())
				.filter(Objects::nonNull)
				.map(ProductSearchDocument::name)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 자동완성 조회에 실패했습니다.", e);
		}
	}

	private ProductSearchHit toHit(ProductSearchDocument document) {
		return new ProductSearchHit(
			document.productId(),
			document.sellerId(),
			document.name(),
			document.description(),
			document.productType(),
			document.model(),
			document.amount(),
			document.thumbnailUrl(),
			document.tags(),
			document.salesCount(),
			document.ratingAvg(),
			document.firstPublishedAt(),
			document.currentVersionAt()
		);
	}
}
