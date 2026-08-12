package com.prompthub.search.infra.es.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.application.indexing.ProductSearchIndexPort;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.config.ProductReindexProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * reviewCount는 아직 집계 쿼리가 없어 0으로 둔다 — 필요해지면
 * ProductRepository에 countActiveReviews(familyRootId) 추가 후 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchIndexer implements ProductSearchIndexPort {

	/** 이 상태 코드로 실패한 item만 1회 재시도한다 — 나머지는 다시 보내도 같은 결과다. */
	private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);
	private static final String PIT_KEEP_ALIVE = "1m";

	private final ElasticsearchClient client;
	private final ProductReindexProperties reindexProperties;

	@Override
	public boolean indexExists() {
		try {
			return client.indices().existsAlias(e -> e.name(ProductIndexBootstrap.ALIAS)).value();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES alias 존재 여부 확인에 실패했습니다.", e);
		}
	}

	/**
	 * alias의 전체 문서 ID를 PIT(point in time) + search_after로 순회한다. 단일 size(10000)
	 * 검색은 그 이상 색인된 문서가 있으면 고아 탐지가 불완전해진다.
	 */
	@Override
	public Set<UUID> findAllIndexedFamilyRootIds() {
		String pitId = openPointInTime();
		try {
			return scanAllIds(pitId);
		} finally {
			closePointInTimeQuietly(pitId);
		}
	}

	@Override
	public void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
		List<BulkOperation> operations = buildOperations(toUpsert, toDelete);
		int chunkSize = reindexProperties.bulkChunkSize();
		for (int start = 0; start < operations.size(); start += chunkSize) {
			List<BulkOperation> chunk = operations.subList(start, Math.min(start + chunkSize, operations.size()));
			executeChunkWithRetry(chunk);
		}
	}

	private List<BulkOperation> buildOperations(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
		List<BulkOperation> operations = new ArrayList<>(toUpsert.size() + toDelete.size());
		for (FamilyUpsertInput input : toUpsert) {
			ProductSearchDocument document = buildDocument(input);
			operations.add(BulkOperation.of(op -> op.index(idx -> idx
				.index(ProductIndexBootstrap.ALIAS)
				.id(document.familyRootId().toString())
				.document(document))));
		}
		for (UUID familyRootId : toDelete) {
			operations.add(BulkOperation.of(op -> op.delete(d -> d
				.index(ProductIndexBootstrap.ALIAS)
				.id(familyRootId.toString()))));
		}
		return operations;
	}

	/** 첫 시도에서 일시적으로 실패한 item만 원본 operation 그대로 한 번 다시 보낸다. */
	private void executeChunkWithRetry(List<BulkOperation> chunk) {
		if (chunk.isEmpty()) {
			return;
		}

		List<BulkResponseItem> firstAttempt = executeBulk(chunk);
		List<BulkOperation> retryTargets = new ArrayList<>();
		for (int i = 0; i < firstAttempt.size(); i++) {
			if (isRetryable(firstAttempt.get(i))) {
				retryTargets.add(chunk.get(i));
			}
		}

		if (retryTargets.isEmpty()) {
			failOnPermanentErrors(firstAttempt);
			return;
		}

		List<BulkResponseItem> retryResult = executeBulk(retryTargets);
		List<BulkResponseItem> finalItems = new ArrayList<>();
		for (BulkResponseItem item : firstAttempt) {
			if (!isRetryable(item)) {
				finalItems.add(item);
			}
		}
		finalItems.addAll(retryResult);
		failOnPermanentErrors(finalItems);
	}

	private List<BulkResponseItem> executeBulk(List<BulkOperation> operations) {
		try {
			BulkResponse response = client.bulk(b -> b.operations(operations));
			return response.items();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 벌크 반영에 실패했습니다.", e);
		}
	}

	/** 실패 item의 문서 ID·operation·상태·사유만 남긴다 — 문서 본문과 embedding은 로그에 남기지 않는다. */
	private void failOnPermanentErrors(List<BulkResponseItem> items) {
		List<BulkResponseItem> failedItems = items.stream().filter(ElasticsearchProductSearchIndexer::isFailure).toList();
		if (failedItems.isEmpty()) {
			return;
		}

		for (BulkResponseItem item : failedItems) {
			log.error("ES bulk item 실패. operation={}, index={}, id={}, status={}, reason={}",
				item.operationType(), item.index(), item.id(), item.status(), item.error().reason());
		}
		throw new IllegalStateException("ES bulk item failures. failedItems=" + failedItems.size());
	}

	/** delete 대상이 이미 없는 404는 목표 상태(문서 없음)가 이미 충족된 것이므로 실패로 보지 않는다. */
	static boolean isFailure(BulkResponseItem item) {
		if (item.error() == null) {
			return false;
		}
		return !(item.operationType() == OperationType.Delete && item.status() == 404);
	}

	static boolean isRetryable(BulkResponseItem item) {
		return isFailure(item) && RETRYABLE_STATUS_CODES.contains(item.status());
	}

	private String openPointInTime() {
		try {
			OpenPointInTimeResponse response = client.openPointInTime(
				p -> p.index(ProductIndexBootstrap.ALIAS).keepAlive(t -> t.time(PIT_KEEP_ALIVE)));
			return response.id();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES point-in-time 열기에 실패했습니다.", e);
		}
	}

	private Set<UUID> scanAllIds(String pitId) {
		Set<UUID> ids = new LinkedHashSet<>();
		List<FieldValue> searchAfter = null;
		int pageSize = reindexProperties.orphanScanPageSize();

		while (true) {
			List<Hit<Void>> hits = searchPage(pitId, pageSize, searchAfter);
			hits.forEach(hit -> ids.add(UUID.fromString(hit.id())));
			if (hits.size() < pageSize) {
				return ids;
			}
			searchAfter = hits.get(hits.size() - 1).sort();
		}
	}

	private List<Hit<Void>> searchPage(String pitId, int pageSize, List<FieldValue> searchAfter) {
		try {
			SearchResponse<Void> response = client.search(s -> {
				s.pit(p -> p.id(pitId).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
					.source(src -> src.fetch(false))
					.size(pageSize)
					.sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
				if (searchAfter != null) {
					s.searchAfter(searchAfter);
				}
				return s;
			}, Void.class);
			return response.hits().hits();
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("ES 색인 목록 조회에 실패했습니다.", e);
		}
	}

	/** 성공·실패와 무관하게 항상 닫는다 — 닫기 자체가 실패해도 keep-alive 만료로 정리되므로 경고만 남긴다. */
	private void closePointInTimeQuietly(String pitId) {
		try {
			client.closePointInTime(c -> c.id(pitId));
		} catch (IOException | RuntimeException e) {
			log.warn("ES point-in-time 닫기에 실패했습니다. keep-alive 만료로 정리됩니다. pitId={}", pitId, e);
		}
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
