package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.FamilyUpsertInput;
import com.prompthub.search.application.ProductSearchIndexer;
import java.io.IOException;
import java.time.LocalDateTime;
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
public class ElasticsearchProductSearchIndexer implements ProductSearchIndexer {

	private final ElasticsearchClient client;

	@Override
	public void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt) {
		ProductSearchDocument document = buildDocument(onSale, familySalesCount, averageRating, firstPublishedAt);
		try {
			client.index(i -> i.index(ProductIndexBootstrap.ALIAS).id(document.familyRootId().toString()).document(document));
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 색인에 실패했습니다. familyRootId=" + document.familyRootId(), e);
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
		try {
			client.bulk(b -> {
				for (FamilyUpsertInput input : toUpsert) {
					ProductSearchDocument document = buildDocument(
						input.onSale(), input.familySalesCount(), input.averageRating(), input.firstPublishedAt());
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
	}

	private ProductSearchDocument buildDocument(
		Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt
	) {
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
			(int) familySalesCount,
			onSale.getViewCount(),
			0,
			averageRating,
			firstPublishedAt,
			onSale.getUpdatedAt()
		);
	}
}
