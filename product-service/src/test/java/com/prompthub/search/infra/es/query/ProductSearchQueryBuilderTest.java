package com.prompthub.search.infra.es.query;

import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.config.SearchRankingProperties;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ProductSearchQueryBuilderTest {

	private final SearchRankingProperties rankingProperties =
		new SearchRankingProperties(0.3, 0.1, 0.1, 0.2, "30d", 0.7);
	private final ProductSearchQueryBuilder queryBuilder = new ProductSearchQueryBuilder(rankingProperties);

	@Test
	void buildQuery_sort가_popular가_아니면_function_score를_씌우지_않는다() {
		Query query = queryBuilder.buildQuery("", "all", "rating");

		assertThat(query.isBool()).isTrue();
		BoolQuery bool = query.bool();
		assertThat(bool.must()).hasSize(1);
		assertThat(bool.must().get(0).isMatchAll()).isTrue();
		assertThat(bool.filter()).isEmpty();
	}

	@Test
	void buildQuery_productType가_all이_아니면_term_필터를_추가한다() {
		Query query = queryBuilder.buildQuery("", "PROMPT", "rating");

		BoolQuery bool = query.bool();
		assertThat(bool.filter()).hasSize(1);
		assertThat(bool.filter().get(0).isTerm()).isTrue();
		assertThat(bool.filter().get(0).term().field()).isEqualTo("productType");
		assertThat(bool.filter().get(0).term().value().stringValue()).isEqualTo("PROMPT");
	}

	@Test
	void buildQuery_keyword이_있으면_multiMatch를_사용한다() {
		Query query = queryBuilder.buildQuery("목업", "all", "rating");

		BoolQuery bool = query.bool();
		assertThat(bool.must().get(0).isMultiMatch()).isTrue();
		MultiMatchQuery multiMatch = bool.must().get(0).multiMatch();
		assertThat(multiMatch.query()).isEqualTo("목업");
		assertThat(multiMatch.fields()).containsExactly("name^3", "tags.text^2", "description^1.5", "model.text");
		assertThat(multiMatch.minimumShouldMatch()).isEqualTo("2<75%");
	}

	@Test
	void buildQuery_sort가_popular면_function_score로_감싸고_4개_함수를_가진다() {
		Query query = queryBuilder.buildQuery("", "all", "popular");

		assertThat(query.isFunctionScore()).isTrue();
		FunctionScoreQuery functionScore = query.functionScore();
		assertThat(functionScore.functions()).hasSize(4);
		assertThat(functionScore.query().isBool()).isTrue();
	}

	@Test
	void buildSort_rating은_ratingAvg_내림차순과_id_타이브레이커를_반환한다() {
		List<SortOptions> sort = queryBuilder.buildSort("rating");

		assertThat(sort).hasSize(2);
		assertThat(sort.get(0).field().field()).isEqualTo("ratingAvg");
		assertThat(sort.get(1).field().field()).isEqualTo("familyRootId");
	}

	@Test
	void buildSort_priceAsc는_amount_오름차순과_id_타이브레이커를_반환한다() {
		List<SortOptions> sort = queryBuilder.buildSort("price-asc");

		assertThat(sort).hasSize(2);
		assertThat(sort.get(0).field().field()).isEqualTo("amount");
		assertThat(sort.get(1).field().field()).isEqualTo("familyRootId");
	}

	@Test
	void buildSort_popular는_score_내림차순과_id_타이브레이커를_반환한다() {
		List<SortOptions> sort = queryBuilder.buildSort("popular");

		assertThat(sort).hasSize(2);
		assertThat(sort.get(0).isScore()).isTrue();
		assertThat(sort.get(1).field().field()).isEqualTo("familyRootId");
	}

	@Test
	void build_page와_size로_from을_계산하고_trackTotalHits를_켠다() {
		SearchRequest request = queryBuilder.build("", "all", "popular", PageRequest.of(3, 10));

		assertThat(request.index()).containsExactly(ProductIndexBootstrap.ALIAS);
		assertThat(request.from()).isEqualTo(30);
		assertThat(request.size()).isEqualTo(10);
		assertThat(request.trackTotalHits().isEnabled()).isTrue();
		assertThat(request.trackTotalHits().enabled()).isTrue();
	}

	@Test
	void buildKnn_유사도_하한을_건다() {
		SearchRequest request = queryBuilder.buildKnn(new float[] {0.1f, 0.2f}, "all", 100);

		// 하한이 없으면 색인 문서가 k보다 적을 때 어떤 질의든 전체 문서가 후보로 들어온다 (#645)
		assertThat(request.knn()).hasSize(1);
		assertThat(request.knn().get(0).similarity()).isNotNull().isPositive();
	}

	@Test
	void buildQualifiedCandidates는_관련성_통과_ID만_가격순으로_조회한다() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		SearchRequest request = queryBuilder.buildQualifiedCandidates(
			List.of(first, second), "price-asc", PageRequest.of(0, 20));

		assertThat(request.query().terms().field()).isEqualTo("_id");
		assertThat(request.query().terms().terms().value())
			.extracting(value -> value.stringValue())
			.containsExactly(first.toString(), second.toString());
		assertThat(request.sort().get(0).field().field()).isEqualTo("amount");
	}

	@Test
	void buildKnn_같은_productType_필터를_어휘_레그와_동일하게_건다() {
		SearchRequest request = queryBuilder.buildKnn(new float[] {0.1f, 0.2f}, "PROMPT", 100);

		List<Query> filters = request.knn().get(0).filter();
		assertThat(filters).hasSize(1);
		assertThat(filters.get(0).term().field()).isEqualTo("productType");
		assertThat(filters.get(0).term().value().stringValue()).isEqualTo("PROMPT");
	}

	@Test
	void buildSuggest_name에_match_phrase_prefix를_쓴다() {
		SearchRequest request = queryBuilder.buildSuggest("프롬", 5);

		Query query = request.query();
		assertThat(query).isNotNull();
		assertThat(query.isMatchPhrasePrefix()).isTrue();
		assertThat(query.matchPhrasePrefix().field()).isEqualTo("name");
		assertThat(query.matchPhrasePrefix().query()).isEqualTo("프롬");
	}

	@Test
	void buildSuggest_salesCount_내림차순으로_정렬한다() {
		SearchRequest request = queryBuilder.buildSuggest("프롬", 5);

		// 목록의 popular 정렬(function_score)을 재사용하지 않는다 —
		// 자동완성은 타이핑 중 수 ms 응답이 요건이라 점수 계산을 얹지 않는다
		assertThat(request.sort()).hasSize(1);
		assertThat(request.sort().get(0).field().field()).isEqualTo("salesCount");
		assertThat(request.sort().get(0).field().order()).isEqualTo(SortOrder.Desc);
	}

	@Test
	void buildSuggest_limit만큼만_요청하고_name만_가져온다() {
		SearchRequest request = queryBuilder.buildSuggest("프롬", 5);

		assertThat(request.index()).containsExactly(ProductIndexBootstrap.ALIAS);
		assertThat(request.size()).isEqualTo(5);
		// 제안어만 필요하므로 문서 전체를 가져오지 않는다
		assertThat(request.source().filter().includes()).containsExactly("name");
	}
}
