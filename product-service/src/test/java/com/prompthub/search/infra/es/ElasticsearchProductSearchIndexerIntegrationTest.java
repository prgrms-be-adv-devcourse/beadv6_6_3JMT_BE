package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ElasticsearchProductSearchIndexerIntegrationTest extends ElasticsearchIntegrationTestSupport {

	@Autowired
	private ElasticsearchClient client;

	@Test
	void upsert_LocalDateTime_필드가_있어도_색인에_성공한다() throws Exception {
		ElasticsearchProductSearchIndexer indexer = new ElasticsearchProductSearchIndexer(client);
		UUID familyRootId = UUID.randomUUID();
		Product product = Product.create(familyRootId, UUID.randomUUID(), ProductContentFixtures.promptContent());

		indexer.upsert(product, 5L, 4.5, LocalDateTime.now());
		client.indices().refresh(r -> r.index(ProductIndexBootstrap.ALIAS));

		var response = client.get(
			g -> g.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()),
			ProductSearchDocument.class);
		assertThat(response.found()).isTrue();
		assertThat(response.source()).isNotNull();
		assertThat(response.source().familyRootId()).isEqualTo(familyRootId);
	}
}
