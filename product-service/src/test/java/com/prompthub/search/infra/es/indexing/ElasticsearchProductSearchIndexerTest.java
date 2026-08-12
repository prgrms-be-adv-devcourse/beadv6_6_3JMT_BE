package com.prompthub.search.infra.es.indexing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchProductSearchIndexerTest {

	@Test
	void validateBulkResponse_throwsWhenAnItemFails() {
		BulkResponse response = BulkResponse.of(builder -> builder
			.errors(true)
			.took(0)
			.items(item -> item
				.operationType(OperationType.Index)
				.id("product-1")
				.index("products")
				.status(400)
				.error(error -> error.reason("mapping failure"))));

		assertThatThrownBy(() -> ElasticsearchProductSearchIndexer.validateBulkResponse(response))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("failedItems=1")
			.hasMessageContaining("mapping failure");
	}

	@Test
	void validateBulkResponse_doesNothingWhenAllItemsSucceed() {
		BulkResponse response = BulkResponse.of(builder -> builder.errors(false).took(0).items(List.of()));

		ElasticsearchProductSearchIndexer.validateBulkResponse(response);
	}
}
