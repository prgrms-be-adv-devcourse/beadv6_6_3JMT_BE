package com.prompthub.search.infra.es.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.infra.es.config.ProductReindexProperties;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ElasticsearchProductSearchIndexerTest {

	@Test
	void isFailure_에러가_없으면_실패가_아니다() {
		BulkResponseItem item = item(OperationType.Index, 200, null);

		assertThat(ElasticsearchProductSearchIndexer.isFailure(item)).isFalse();
	}

	@Test
	void isFailure_delete_404는_실패로_보지_않는다() {
		BulkResponseItem item = item(OperationType.Delete, 404, "not_found");

		assertThat(ElasticsearchProductSearchIndexer.isFailure(item)).isFalse();
	}

	@Test
	void isFailure_index_400은_실패다() {
		BulkResponseItem item = item(OperationType.Index, 400, "mapping failure");

		assertThat(ElasticsearchProductSearchIndexer.isFailure(item)).isTrue();
	}

	@Test
	void isRetryable_429는_재시도_대상이다() {
		BulkResponseItem item = item(OperationType.Index, 429, "es_rejected_execution_exception");

		assertThat(ElasticsearchProductSearchIndexer.isRetryable(item)).isTrue();
	}

	@Test
	void isRetryable_영구_실패는_재시도_대상이_아니다() {
		BulkResponseItem item = item(OperationType.Index, 400, "mapping failure");

		assertThat(ElasticsearchProductSearchIndexer.isRetryable(item)).isFalse();
	}

	@Test
	void isRetryable_delete_404는_애초에_실패가_아니므로_재시도_대상이_아니다() {
		BulkResponseItem item = item(OperationType.Delete, 404, "not_found");

		assertThat(ElasticsearchProductSearchIndexer.isRetryable(item)).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void bulkReconcile_첫_시도에서_retryable_item만_원본_operation_그대로_1회_재전송한다() throws Exception {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 1000));

		// 순서대로 성공 / 일시적 실패(429, 재시도 대상) / 영구 실패(400)
		BulkResponse firstAttempt = BulkResponse.of(b -> b.errors(true).took(1).items(
			item(OperationType.Index, 200, null),
			item(OperationType.Index, 429, "es_rejected_execution_exception"),
			item(OperationType.Index, 400, "mapping failure")));
		// 재전송 대상은 두 번째 item(429) 하나뿐이어야 하고, 이번엔 성공한다.
		BulkResponse retryAttempt = BulkResponse.of(b -> b.errors(false).took(1).items(
			item(OperationType.Index, 200, null)));
		given(client.bulk(any(Function.class))).willReturn(firstAttempt, retryAttempt);

		List<FamilyUpsertInput> toUpsert = List.of(upsertInput(), upsertInput(), upsertInput());

		// 재전송 후에도 영구 실패(400) 1건은 남으므로 최종적으로 예외가 나야 한다.
		assertThatThrownBy(() -> indexer.bulkReconcile(toUpsert, List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("failedItems=1");

		// bulk가 정확히 2번(첫 시도 + 재시도 1회) 호출됐는지 — 무한 재시도가 아님을 보장한다.
		then(client).should(times(2)).bulk(any(Function.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void findAllIndexedFamilyRootIds_조회중_예외가_나도_PIT을_닫는다() throws Exception {
		ElasticsearchClient client = mock(ElasticsearchClient.class);
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 1000));

		given(client.openPointInTime(any(Function.class)))
			.willReturn(OpenPointInTimeResponse.of(b -> b.id("pit-id")));
		given(client.search(any(Function.class), eq(Void.class)))
			.willThrow(new IOException("connection reset"));

		assertThatThrownBy(indexer::findAllIndexedFamilyRootIds)
			.isInstanceOf(IllegalStateException.class);

		// 조회가 실패해도 PIT는 finally에서 반드시 닫혀야 한다 — 안 닫으면 keep-alive 동안 리소스가 샌다.
		then(client).should().closePointInTime(any(Function.class));
	}

	private FamilyUpsertInput upsertInput() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent());
		return new FamilyUpsertInput(product, 1L, 1L, 4.0, LocalDateTime.now(), null);
	}

	private BulkResponseItem item(OperationType operationType, int status, String errorReason) {
		return BulkResponseItem.of(builder -> {
			builder.operationType(operationType).id("family-1").index("products").status(status);
			if (errorReason != null) {
				builder.error(error -> error.reason(errorReason));
			}
			return builder;
		});
	}
}
