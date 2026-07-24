package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

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
		assertThat(multiMatch.fields()).containsExactly("name^3", "tags.text^2", "description^1.5", "content");
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
		SearchRequest request = queryBuilder.build("", "all", "popular", 3, 10);

		assertThat(request.index()).containsExactly(ProductIndexBootstrap.ALIAS);
		assertThat(request.from()).isEqualTo(20);
		assertThat(request.size()).isEqualTo(10);
		assertThat(request.trackTotalHits().isEnabled()).isTrue();
		assertThat(request.trackTotalHits().enabled()).isTrue();
	}
}
