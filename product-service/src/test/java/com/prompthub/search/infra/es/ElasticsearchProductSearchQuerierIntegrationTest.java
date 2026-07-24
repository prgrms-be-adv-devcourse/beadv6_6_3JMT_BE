package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.support.ProductContentFixtures;
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
		indexer.upsert(product, salesCount, viewCount, ratingAvg, LocalDateTime.now());
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
		// 공유 ES 컨테이너에 다른 테스트가 남긴 문서가 섞여도 total이 어긋나지 않도록,
		// 이 테스트만의 고유 키워드로 결과를 격리한다(정렬·페이징 자체는 그대로 검증됨).
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

		List<UUID> collected = new ArrayList<>();
		long total = -1;
		for (int page = 0; page < 3; page++) {
			ProductSearchPageResult result = querier().search(uniqueKeyword, "all", "price-asc", PageRequest.of(page, 2));
			total = result.total();
			collected.addAll(result.hits().stream().map(ProductSearchHit::productId).toList());
		}

		assertThat(total).isEqualTo(5);
		assertThat(collected).containsExactlyInAnyOrderElementsOf(allIds);
		assertThat(collected).doesNotHaveDuplicates();
	}
}
