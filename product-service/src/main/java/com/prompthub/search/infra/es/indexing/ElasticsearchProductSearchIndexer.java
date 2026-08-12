package com.prompthub.search.infra.es.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.application.indexing.ProductSearchIndexPort;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * reviewCount는 아직 집계 쿼리가 없어 0으로 둔다 — 필요해지면
 * ProductRepository에 countActiveReviews(familyRootId) 추가 후 채운다.
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchIndexer implements ProductSearchIndexPort {

	private final ElasticsearchClient client;

	@Override
	public void upsert(FamilyUpsertInput input) {
		ProductSearchDocument document = buildDocument(input);
		try {
			client.index(i -> i.index(ProductIndexBootstrap.ALIAS).id(document.familyRootId().toString()).document(document));
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 색인에 실패했습니다. familyRootId=" + document.familyRootId(), e);
		}
	}

	@Override
	public boolean indexExists() {
		try {
			return client.indices().existsAlias(e -> e.name(ProductIndexBootstrap.ALIAS)).value();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES alias 존재 여부 확인에 실패했습니다.", e);
		}
	}

	@Override
	public Set<UUID> findAllIndexedFamilyRootIds() {
		try {
			SearchResponse<Void> response = client.search(s -> s
				.index(ProductIndexBootstrap.ALIAS)
				.source(src -> src.fetch(false))
				.size(10000), Void.class);
			return response.hits().hits().stream()
				.map(Hit::id)
				.filter(Objects::nonNull)
				.map(UUID::fromString)
				.collect(Collectors.toSet());
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 색인 목록 조회에 실패했습니다.", e);
		}
	}

	@Override
	public void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
		if (toUpsert.isEmpty() && toDelete.isEmpty()) {
			return;
		}
		BulkResponse response;
		try {
			response = client.bulk(b -> {
				for (FamilyUpsertInput input : toUpsert) {
					ProductSearchDocument document = buildDocument(input);
					b.operations(op -> op.index(idx -> idx
						.index(ProductIndexBootstrap.ALIAS)
						.id(document.familyRootId().toString())
						.document(document)));
				}
				for (UUID familyRootId : toDelete) {
					b.operations(op -> op.delete(d -> d
						.index(ProductIndexBootstrap.ALIAS)
						.id(familyRootId.toString())));
				}
				return b;
			});
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 벌크 반영에 실패했습니다.", e);
		}
		validateBulkResponse(response);
	}

	static void validateBulkResponse(BulkResponse response) {
		if (!response.errors()) {
			return;
		}

		List<BulkResponseItem> failedItems = response.items().stream()
			.filter(item -> item.error() != null)
			.toList();
		String details = failedItems.stream()
			.limit(5)
			.map(item -> "operation=" + item.operationType()
				+ ", index=" + item.index()
				+ ", id=" + item.id()
				+ ", reason=" + item.error().reason())
			.collect(Collectors.joining("; "));

		throw new IllegalStateException(
			"ES bulk item failures. failedItems=" + failedItems.size() + ", details=" + details);
	}

	private ProductSearchDocument buildDocument(FamilyUpsertInput input) {
		Product onSale = input.onSale();
		return new ProductSearchDocument(
			onSale.familyRootId(),
			onSale.getId(),
			onSale.getSellerId(),
			onSale.getName(),
			onSale.getDescription(),
			onSale.getContent(),
			onSale.getTags(),
			onSale.getProductType().name(),
			onSale.getModel(),
			onSale.getAmount(),
			onSale.getAmountType().name(),
			onSale.getThumbnailUrl(),
			onSale.getBadge(),
			(int) input.familySalesCount(),
			(int) input.familyViewCount(),
			0,
			input.averageRating(),
			input.firstPublishedAt(),
			onSale.getUpdatedAt(),
			input.embedding()
		);
	}
}
