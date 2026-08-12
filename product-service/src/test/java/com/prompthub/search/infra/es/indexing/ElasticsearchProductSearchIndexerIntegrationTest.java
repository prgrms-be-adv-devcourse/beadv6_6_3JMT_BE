package com.prompthub.search.infra.es.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ElasticsearchProductSearchIndexerIntegrationTest extends ElasticsearchIntegrationTestSupport {

	/** ES 매핑의 dense_vector dims와 같아야 한다(products-v1-mapping.json). */
	private static final int EMBEDDING_DIMENSIONS = 1536;

	@Autowired
	private ElasticsearchClient client;

	@Test
	void upsert_LocalDateTime_필드가_있어도_색인에_성공한다() throws Exception {
		ElasticsearchProductSearchIndexer indexer = new ElasticsearchProductSearchIndexer(client);
		UUID familyRootId = UUID.randomUUID();
		Product product = Product.create(familyRootId, UUID.randomUUID(), ProductContentFixtures.promptContent());

		float[] embedding = new float[EMBEDDING_DIMENSIONS];
		embedding[0] = 0.1f;
		embedding[1] = 0.2f;
		indexer.upsert(new FamilyUpsertInput(product, 5L, 3L, 4.5, LocalDateTime.now(), embedding));
		client.indices().refresh(r -> r.index(ProductIndexBootstrap.ALIAS));

		var response = client.get(
			g -> g.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()),
			ProductSearchDocument.class);
		assertThat(response.found()).isTrue();
		assertThat(response.source()).isNotNull();
		assertThat(response.source().familyRootId()).isEqualTo(familyRootId);
	}

	@Test
	void indexExists_부트스트랩으로_생성된_alias가_있으면_true를_반환한다() {
		ElasticsearchProductSearchIndexer indexer = new ElasticsearchProductSearchIndexer(client);

		assertThat(indexer.indexExists()).isTrue();
	}
}
