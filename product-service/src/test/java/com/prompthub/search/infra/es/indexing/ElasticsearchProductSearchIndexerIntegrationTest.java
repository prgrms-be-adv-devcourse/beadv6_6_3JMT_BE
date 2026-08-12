package com.prompthub.search.infra.es.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.config.ProductReindexProperties;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ElasticsearchProductSearchIndexerIntegrationTest extends ElasticsearchIntegrationTestSupport {

	/** ES 매핑의 dense_vector dims와 같아야 한다(products-v1-mapping.json). */
	private static final int EMBEDDING_DIMENSIONS = 1536;

	@Autowired
	private ElasticsearchClient client;

	@Test
	void bulkReconcile_LocalDateTime_필드가_있어도_색인에_성공한다() throws Exception {
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 1000));
		UUID familyRootId = UUID.randomUUID();
		Product product = Product.create(familyRootId, UUID.randomUUID(), ProductContentFixtures.promptContent());

		float[] embedding = new float[EMBEDDING_DIMENSIONS];
		embedding[0] = 0.1f;
		embedding[1] = 0.2f;
		indexer.bulkReconcile(List.of(new FamilyUpsertInput(product, 5L, 3L, 4.5, LocalDateTime.now(), embedding)), List.of());
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
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 1000));

		assertThat(indexer.indexExists()).isTrue();
	}

	@Test
	void bulkReconcile_chunk_크기를_넘는_upsert도_전부_반영한다() throws Exception {
		// chunk 크기를 2로 좁혀 5건을 여러 chunk로 나눠 보내도 전부 반영되는지 확인한다.
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(2, 1000));
		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			toUpsert.add(upsertInput());
		}

		indexer.bulkReconcile(toUpsert, List.of());
		client.indices().refresh(r -> r.index(ProductIndexBootstrap.ALIAS));

		Set<UUID> indexedIds = indexer.findAllIndexedFamilyRootIds();
		List<UUID> expectedIds = toUpsert.stream().map(input -> input.onSale().familyRootId()).toList();
		assertThat(indexedIds).containsAll(expectedIds);
	}

	@Test
	void bulkReconcile_이미_없는_문서_delete는_실패로_보지_않는다() {
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 1000));

		assertThatCode(() -> indexer.bulkReconcile(List.of(), List.of(UUID.randomUUID())))
			.doesNotThrowAnyException();
	}

	@Test
	void findAllIndexedFamilyRootIds_페이지_크기를_넘는_문서도_PIT로_모두_순회한다() throws Exception {
		// page size를 2로 좁혀 5건이 3페이지에 걸쳐 나뉘어도 search_after로 전부 모이는지 확인한다.
		ElasticsearchProductSearchIndexer indexer =
			new ElasticsearchProductSearchIndexer(client, new ProductReindexProperties(500, 2));
		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			toUpsert.add(upsertInput());
		}
		indexer.bulkReconcile(toUpsert, List.of());
		client.indices().refresh(r -> r.index(ProductIndexBootstrap.ALIAS));

		Set<UUID> indexedIds = indexer.findAllIndexedFamilyRootIds();

		List<UUID> expectedIds = toUpsert.stream().map(input -> input.onSale().familyRootId()).toList();
		assertThat(indexedIds).containsAll(expectedIds);
	}

	private FamilyUpsertInput upsertInput() {
		UUID familyRootId = UUID.randomUUID();
		Product product = Product.create(familyRootId, UUID.randomUUID(), ProductContentFixtures.promptContent());
		return new FamilyUpsertInput(product, 1L, 1L, 4.0, LocalDateTime.now(), null);
	}
}
