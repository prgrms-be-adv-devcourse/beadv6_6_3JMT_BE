package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.prompthub.search.application.ProductSearchHit;
import com.prompthub.search.application.ProductSearchPageResult;
import com.prompthub.search.application.ProductSearchQueryService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchQuerier implements ProductSearchQueryService {

	private final ElasticsearchClient client;
	private final ProductSearchQueryBuilder queryBuilder;

	@Override
	public ProductSearchPageResult search(String keyword, String productType, String sort, int page, int size) {
		SearchRequest request = queryBuilder.build(keyword, productType, sort, page, size);
		try {
			SearchResponse<ProductSearchDocument> response = client.search(request, ProductSearchDocument.class);
			List<ProductSearchHit> hits = response.hits().hits().stream()
				.map(hit -> toHit(hit.source()))
				.toList();
			long total = response.hits().total() != null ? response.hits().total().value() : hits.size();
			return new ProductSearchPageResult(hits, total);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 검색에 실패했습니다.", e);
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
