package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.application.FamilyUpsertInput;
import com.prompthub.search.application.ProductSearchHit;
import com.prompthub.search.application.ProductSearchPageResult;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

class ElasticsearchProductSearchQuerierIntegrationTest extends ElasticsearchIntegrationTestSupport {

	@Autowired
	private ElasticsearchClient client;

	private ElasticsearchProductSearchQuerier querier() {
		SearchRankingProperties rankingProperties = new SearchRankingProperties(0.3, 0.1, 0.1, 0.2, "30d", 0.7);
		return new ElasticsearchProductSearchQuerier(client, new ProductSearchQueryBuilder(rankingProperties));
	}

	private void index(Product product, long salesCount, long viewCount, double ratingAvg) {
		ElasticsearchProductSearchIndexer indexer = new ElasticsearchProductSearchIndexer(client);
		indexer.upsert(new FamilyUpsertInput(product, salesCount, viewCount, ratingAvg, LocalDateTime.now()));
	}

	private void refresh() throws Exception {
		client.indices().refresh(r -> r.index(ProductIndexBootstrap.ALIAS));
	}

	@Test
	void search_rating_정렬은_ratingAvg_내림차순으로_반환한다() throws Exception {
		Product low = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("낮은평점", 1000));
		Product high = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("높은평점", 1000));
		index(low, 0, 0, 2.0);
		index(high, 0, 0, 4.5);
		refresh();

		ProductSearchPageResult result = querier().search("", "all", "rating", PageRequest.of(0, 20));

		List<UUID> orderedIds = result.hits().stream().filter(h -> h.productId().equals(low.getId()) || h.productId().equals(high.getId()))
			.map(ProductSearchHit::productId).toList();
		assertThat(orderedIds).containsExactly(high.getId(), low.getId());
	}

	@Test
	void search_priceAsc_정렬은_amount_오름차순으로_반환한다() throws Exception {
		Product cheap = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("저렴한상품", 1000));
		Product expensive = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("비싼상품", 9000));
		index(cheap, 0, 0, 0);
		index(expensive, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier().search("", "all", "price-asc", PageRequest.of(0, 20));

		List<UUID> orderedIds = result.hits().stream()
			.filter(h -> h.productId().equals(cheap.getId()) || h.productId().equals(expensive.getId()))
			.map(ProductSearchHit::productId).toList();
		assertThat(orderedIds).containsExactly(cheap.getId(), expensive.getId());
	}

	@Test
	void search_popular_정렬은_salesCount가_높은_상품을_먼저_반환한다() throws Exception {
		// 고유 키워드로 격리 — 공유 ES 컨테이너에 다른 테스트 문서가 섞여도 순서 검증에 영향 없게 함.
		String uniqueKeyword = "POPULARORDER" + UUID.randomUUID().toString().substring(0, 8);
		Product lowSales = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.promptContent(uniqueKeyword + " 인기낮음", 1000));
		Product highSales = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.promptContent(uniqueKeyword + " 인기높음", 1000));
		index(lowSales, 1, 0, 0);
		index(highSales, 500, 0, 0);
		refresh();

		ProductSearchPageResult result = querier().search(uniqueKeyword, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::productId)
			.containsExactly(highSales.getId(), lowSales.getId());
	}

	@Test
	void search_q로_nori_형태소_검색이_매칭된다() throws Exception {
		Product apple = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("빨간 사과 목업 생성기", 1000));
		Product banana = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("노란 바나나 목업 생성기", 1000));
		index(apple, 0, 0, 0);
		index(banana, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier().search("사과", "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::productId).contains(apple.getId());
		assertThat(result.hits()).extracting(ProductSearchHit::productId).doesNotContain(banana.getId());
	}

	@Test
	void search_productType_필터가_다른_유형을_제외한다() throws Exception {
		Product prompt = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent("프롬프트유형상품", 1000));
		Product notion = Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.notionContent("노션유형상품", 1000));
		index(prompt, 0, 0, 0);
		index(notion, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier().search("", "PROMPT", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::productId).contains(prompt.getId());
		assertThat(result.hits()).extracting(ProductSearchHit::productId).doesNotContain(notion.getId());
	}

	@Test
	void search_페이지네이션은_중복_누락_없이_전체_건수를_커버한다() throws Exception {
		// 공유 ES 컨테이너에 다른 테스트(또는 CI 풀스위트 동시 실행)가 남긴 문서가 키워드에
		// 우연히 걸려 total이 예상보다 커질 수 있다(실제로 CI에서 관측됨: expected 5 but was 7).
		// 그래서 total을 고정값으로 단정하지 않고, 응답이 보고하는 total만큼 페이지를 끝까지
		// 순회한 뒤, 우리가 만든 5개 id만 걸러내 "중복/누락 없이 전부 커버됐는지"를 검증한다.
		String uniqueKeyword = "PAGINGTEST" + UUID.randomUUID().toString().substring(0, 8);
		List<Product> products = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Product product = Product.create(
				UUID.randomUUID(), UUID.randomUUID(),
				ProductContentFixtures.promptContent(uniqueKeyword + " 상품" + i, 1000 + i * 100));
			index(product, 0, 0, 0);
			products.add(product);
		}
		refresh();
		List<UUID> allIds = products.stream().map(Product::getId).toList();

		int size = 2;
		List<UUID> collected = new ArrayList<>();
		long total;
		int page = 0;
		do {
			ProductSearchPageResult result = querier().search(uniqueKeyword, "all", "price-asc", PageRequest.of(page, size));
			total = result.total();
			collected.addAll(result.hits().stream().map(ProductSearchHit::productId).toList());
			page++;
		} while ((long) page * size < total);

		assertThat(total).isGreaterThanOrEqualTo(5);
		List<UUID> ourCollected = collected.stream().filter(allIds::contains).toList();
		assertThat(ourCollected).containsExactlyInAnyOrderElementsOf(allIds);
		assertThat(ourCollected).doesNotHaveDuplicates();
	}
}
