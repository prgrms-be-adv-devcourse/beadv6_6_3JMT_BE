package com.prompthub.search.infra.es.query;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.support.ProductContentFixtures;
import com.prompthub.search.application.embedding.EmbeddingClient;
import com.prompthub.search.application.embedding.QueryEmbeddingCache;
import com.prompthub.search.application.indexing.FamilyUpsertInput;
import com.prompthub.search.application.query.ProductSearchHit;
import com.prompthub.search.application.query.ProductSearchPageResult;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.config.SearchRankingProperties;
import com.prompthub.search.infra.es.indexing.ElasticsearchProductSearchIndexer;
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
		return querier(new RecordingEmbeddingClient());
	}

	private ElasticsearchProductSearchQuerier querier(RecordingEmbeddingClient embeddingClient) {
		SearchRankingProperties rankingProperties = new SearchRankingProperties(0.3, 0.1, 0.1, 0.2, "30d", 0.7);
		return new ElasticsearchProductSearchQuerier(
			client,
			new ProductSearchQueryBuilder(rankingProperties),
			new QueryEmbeddingCache(embeddingClient));
	}

	private void index(Product product, long salesCount, long viewCount, double ratingAvg) {
		index(product, salesCount, viewCount, ratingAvg, null);
	}

	private void index(Product product, long salesCount, long viewCount, double ratingAvg, float[] embedding) {
		ElasticsearchProductSearchIndexer indexer = new ElasticsearchProductSearchIndexer(client);
		indexer.upsert(new FamilyUpsertInput(product, salesCount, viewCount, ratingAvg, LocalDateTime.now(), embedding));
	}

	/** 한 축만 1인 단위 벡터. 서로 다른 축이면 코사인 거리가 최대라 순서가 뚜렷하게 갈린다. */
	private float[] vector(int hotDimension) {
		float[] embedding = new float[1536];
		embedding[hotDimension] = 1f;
		return embedding;
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
	void search_q로_상품명_단어_검색이_매칭된다() throws Exception {
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

	@Test
	void suggest_상품명_중간_단어로도_제안된다() throws Exception {
		// 이 서비스의 상품명은 "시니어 코드리뷰 프롬프트"처럼 앞에 수식어가 붙는다.
		// 상품명 첫 글자로만 매칭하면 "코드"로는 아무것도 안 나온다 —
		// match_phrase_prefix가 중간 단어에서도 앞부분 매칭을 하는지가 이 기능의 전제다.
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String name = "시니어 " + tag + " 코드리뷰 프롬프트";
		index(product(name), 0, 0, 0);
		refresh();

		List<String> suggestions = querier().suggest("코드", 20);

		assertThat(suggestions).contains(name);
	}

	@Test
	void suggest_salesCount_높은_상품명을_먼저_반환한다() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		index(product(tag + " 적게팔린"), 3, 0, 0);
		index(product(tag + " 많이팔린"), 100, 0, 0);
		refresh();

		List<String> suggestions = querier().suggest(tag, 5);

		assertThat(suggestions).containsExactly(tag + " 많이팔린", tag + " 적게팔린");
	}

	@Test
	void suggest_limit만큼만_반환한다() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		for (int i = 0; i < 5; i++) {
			index(product(tag + " 상품" + i), i, 0, 0);
		}
		refresh();

		List<String> suggestions = querier().suggest(tag, 2);

		assertThat(suggestions).hasSize(2);
	}

	@Test
	void 하이브리드_글자가_겹치지_않아도_의미가_가까우면_결과가_나온다() throws Exception {
		// 이 기능의 존재 이유다. 검색어의 어떤 글자도 상품에 들어 있지 않은데 결과가 나와야 한다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product near = product(unique + " 가까운상품");
		Product far = product(unique + " 먼상품");
		index(near, 0, 0, 0, vector(0));
		index(far, 0, 0, 0, vector(1));
		refresh();

		String query = "글자가겹치지않는질의";

		// 먼저 글자 기반만으로는 0건임을 확인한다. 이게 없으면 아래 검증이 kNN 덕분인지
		// 원래 lexical로도 나오는 건지 구분되지 않는다.
		ProductSearchPageResult lexicalOnly = querier(new RecordingEmbeddingClient(null))
			.search(query, "all", "popular", PageRequest.of(0, 20));
		assertThat(lexicalOnly.hits()).isEmpty();

		RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(vector(0));
		ProductSearchPageResult result = querier(embeddingClient)
			.search(query, "all", "popular", PageRequest.of(0, 20));

		assertThat(embeddingClient.calls).hasSize(1);
		assertThat(result.hits()).extracting(ProductSearchHit::name).contains(near.getName());
	}

	@Test
	void 하이브리드_두_레그_모두_상위인_문서가_먼저_온다() throws Exception {
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product both = product(unique + " 양쪽상위");
		Product lexicalOnly = product(unique + " 글자만");
		index(both, 0, 0, 0, vector(0));
		index(lexicalOnly, 0, 0, 0, vector(1));
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(vector(0)))
			.search(unique, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::name).startsWith(both.getName());
	}

	@Test
	void 하이브리드_rating_정렬은_임베딩을_만들지_않는다() throws Exception {
		index(product("정렬테스트"), 0, 0, 3.0, vector(0));
		refresh();

		RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(vector(0));
		querier(embeddingClient).search("정렬테스트", "all", "rating", PageRequest.of(0, 20));

		assertThat(embeddingClient.calls).isEmpty();
	}

	@Test
	void 하이브리드_검색어가_없으면_임베딩을_만들지_않는다() throws Exception {
		index(product("목록조회"), 0, 0, 0, vector(0));
		refresh();

		RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(vector(0));
		querier(embeddingClient).search("", "all", "popular", PageRequest.of(0, 20));

		assertThat(embeddingClient.calls).isEmpty();
	}

	@Test
	void 하이브리드_임베딩을_못_받으면_글자_기반_결과와_같다() throws Exception {
		String unique = UUID.randomUUID().toString().substring(0, 8);
		index(product(unique + " 첫번째"), 10, 0, 0, vector(0));
		index(product(unique + " 두번째"), 5, 0, 0, vector(1));
		refresh();

		List<String> lexicalOnly = querier(new RecordingEmbeddingClient(null))
			.search(unique, "all", "popular", PageRequest.of(0, 20))
			.hits().stream().map(ProductSearchHit::name).toList();
		List<String> viaLexicalPath = querier(new RecordingEmbeddingClient(null))
			.search(unique, "all", "popular", PageRequest.of(0, 20))
			.hits().stream().map(ProductSearchHit::name).toList();

		assertThat(lexicalOnly).isEqualTo(viaLexicalPath).isNotEmpty();
	}

	@Test
	void 하이브리드_productType_필터가_두_레그에_같이_걸린다() throws Exception {
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product prompt = product(unique + " 프롬프트유형");
		Product notion = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.notionContent(unique + " 노션유형", 1000));
		index(prompt, 0, 0, 0, vector(0));
		index(notion, 0, 0, 0, vector(0));
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(vector(0)))
			.search(unique, "NOTION", "popular", PageRequest.of(0, 20));

		// 필터가 한쪽 레그에만 걸리면 PROMPT 상품이 kNN 레그로 새어 들어온다.
		assertThat(result.hits()).extracting(ProductSearchHit::name).containsExactly(notion.getName());
	}

	@Test
	void 하이브리드_글자도_안_맞고_의미도_멀면_0건이다() throws Exception {
		// #645 — kNN은 관련도와 무관하게 상위 k건을 채워 돌려준다. 유사도 하한이 없으면
		// 어떤 질의든 색인 문서 전체가 결과가 되어 "검색 결과 없음"이 발생할 수 없다.
		//
		// 질의 벡터는 다른 테스트가 쓰지 않는 축을 쓴다. 공유 ES 컨테이너의 기존 문서는
		// vector(0)·vector(1)이라 이 축과 직교(코사인 0)해 하한에 걸려 전부 빠진다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		index(product(unique + " 먼상품"), 0, 0, 0, vector(9));
		refresh();

		String query = "글자도안맞고의미도먼질의" + unique;
		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(vector(8)))
			.search(query, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).isEmpty();
		assertThat(result.total()).isZero();
	}

	@Test
	void 하이브리드_total은_실제_반환_건수보다_작지_않다() throws Exception {
		// #645 — total을 글자 기반 레그에서만 가져오면 의미 기반만 찾은 문서가 빠져
		// total < 실제 목록이 되고, hasNext가 false로 굳어 다음 페이지로 넘어갈 수 없다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product lexicalHit = product(unique + " 글자로걸림");
		Product semanticOnly = product("글자로안걸림" + unique + "X");
		index(lexicalHit, 0, 0, 0, vector(1));
		index(semanticOnly, 0, 0, 0, vector(0));
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(vector(0)))
			.search(unique, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::name)
			.contains(lexicalHit.getName(), semanticOnly.getName());
		assertThat(result.total()).isGreaterThanOrEqualTo(result.hits().size());
	}

	@Test
	void 본문에만_있는_단어는_글자_검색에_걸리지_않는다() throws Exception {
		// #689 — 프롬프트 본문은 "예: 일별 주식 지수 데이터 수집" 같은 placeholder 예시로
		// 가득해, 본문을 검색 대상에 두면 상품과 무관한 검색어가 예시 문구에 걸려 오탐이 된다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.promptContent("배치 가이드", 1000, "예: 일별 " + unique + " 지수 데이터 수집"));
		index(product, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(null))
			.search(unique, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).isEmpty();
		assertThat(result.total()).isZero();
	}

	@Test
	void 두_단어_검색은_한_단어만_겹치는_상품을_반환하지_않는다() throws Exception {
		// #689 — 대부분의 상품명이 "프롬프트"를 포함해, OR 매칭이면 그 단어 하나로
		// 무관한 상품("펭귄 생성 프롬프트")까지 꼬리로 딸려온다. minimum_should_match가 막는다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product both = product(unique + " 주식 도구");
		Product oneWordOnly = product("펭귄 생성 도구");
		index(both, 0, 0, 0);
		index(oneWordOnly, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(null))
			.search(unique + " 도구", "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::name).containsExactly(both.getName());
	}

	@Test
	void 검색어의_유형_단어는_글자가_아니라_유형_필터로_해석된다() throws Exception {
		// #689 — "주식 프롬프트"의 의도는 "프롬프트 유형 중 주식 관련"이다. 유형 단어를 글자로
		// 매칭하면 이름에 "프롬프트"가 없는 상품(주식 자동화류)이 빠지고, 이름에 단 무관 상품이 딸려온다.
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product promptTyped = product(unique + " 자동화");
		Product notionTyped = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.notionContent(unique + " 자동화 계획표", 1000));
		index(promptTyped, 0, 0, 0);
		index(notionTyped, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(null))
			.search(unique + " 프롬프트", "all", "popular", PageRequest.of(0, 20));

		// 이름에 "프롬프트"가 없어도 PROMPT 유형이면 나오고, NOTION 유형은 필터로 걸러진다.
		assertThat(result.hits()).extracting(ProductSearchHit::name).containsExactly(promptTyped.getName());
	}

	@Test
	void 모델명으로도_글자_검색이_된다() throws Exception {
		// #699 — 화면에 모델 뱃지가 보이는데 그 단어로 검색이 0건이면 사용자에겐 고장이다.
		// 이름·태그·설명에 없어도 model 필드로 매칭되어야 한다(가중치 최하).
		String uniqueModel = "m" + UUID.randomUUID().toString().substring(0, 8);
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
			ProductType.PROMPT, "모델검색상품", "설명", uniqueModel + " ultra",
			AmountType.PAID, 1000, null, List.of(), "content", null, null, List.of()));
		index(product, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(null))
			.search(uniqueModel, "all", "popular", PageRequest.of(0, 20));

		assertThat(result.hits()).extracting(ProductSearchHit::name).containsExactly(product.getName());
	}

	@Test
	void 유형_단어만_검색하면_그_유형_전체가_나온다() throws Exception {
		String unique = UUID.randomUUID().toString().substring(0, 8);
		Product notionTyped = Product.create(UUID.randomUUID(), UUID.randomUUID(),
			ProductContentFixtures.notionContent(unique + " 계획표", 1000));
		index(notionTyped, 0, 0, 0);
		refresh();

		ProductSearchPageResult result = querier(new RecordingEmbeddingClient(null))
			.search("노션", "all", "popular", PageRequest.of(0, 100));

		// 공유 색인이라 정확한 목록 대신 "전부 NOTION 유형 + 방금 넣은 상품 포함"을 확인한다.
		assertThat(result.hits()).isNotEmpty().allMatch(hit -> "NOTION".equals(hit.productType()));
		assertThat(result.hits()).extracting(ProductSearchHit::name).contains(notionTyped.getName());
	}

	private Product product(String name) {
		return Product.create(UUID.randomUUID(), UUID.randomUUID(), ProductContentFixtures.promptContent(name, 1000));
	}

	private static final class RecordingEmbeddingClient implements EmbeddingClient {

		private final List<String> calls = new ArrayList<>();
		private final float[] embedding;

		private RecordingEmbeddingClient() {
			this(null);
		}

		private RecordingEmbeddingClient(float[] embedding) {
			this.embedding = embedding;
		}

		@Override
		public float[] embed(String text) {
			calls.add(text);
			return embedding;
		}
	}
}
