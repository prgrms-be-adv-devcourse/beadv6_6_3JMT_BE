package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * ES 검색 요청을 순수하게 구성한다(I/O 없음 — ES 없이 유닛 테스트 가능).
 * 실행은 {@link ElasticsearchProductSearchQuerier}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class ProductSearchQueryBuilder {

	private static final List<String> MATCH_FIELDS = List.of("name^3", "tags.text^2", "description^1.5", "content");
	private static final String ALL_PRODUCT_TYPES = "all";
	private static final String SORT_POPULAR = "popular";
	private static final String SORT_RATING = "rating";
	private static final String SORT_PRICE_ASC = "price-asc";

	private final SearchRankingProperties rankingProperties;

	public SearchRequest build(String keyword, String productType, String sort, Pageable pageable) {
		Query query = buildQuery(keyword, productType, sort);
		List<SortOptions> sortOptions = buildSort(sort);

		return SearchRequest.of(s -> s
			.index(ProductIndexBootstrap.ALIAS)
			.query(query)
			.sort(sortOptions)
			.from((int) pageable.getOffset())
			.size(pageable.getPageSize())
			.trackTotalHits(t -> t.enabled(true)));
	}

	/**
	 * 자동완성 제안 요청을 구성한다.
	 *
	 * <p>목록의 {@code popular} 정렬(function_score)을 재사용하지 않고 {@code salesCount}
	 * 단일 기준으로 정렬한다 — 자동완성은 타이핑 중 수 ms 응답이 요건이라 점수 계산을 얹지
	 * 않고, 제안어 몇 개를 고르는 데 신선도·평점의 기여가 미미하다고 판단했다.
	 *
	 * <p>{@code match_phrase_prefix}는 마지막 토큰을 prefix로 처리하므로 상품명이 "시니어
	 * 코드리뷰 프롬프트"처럼 여러 단어여도 중간 단어의 앞부분("코드")으로 걸린다. 형태소
	 * 분석기(nori) 없이 기본 분석기로 동작한다 — 자세한 근거는 #379 참고.
	 */
	public SearchRequest buildSuggest(String keyword, int limit) {
		return SearchRequest.of(s -> s
			.index(ProductIndexBootstrap.ALIAS)
			.query(q -> q.matchPhrasePrefix(m -> m.field("name").query(keyword)))
			.sort(so -> so.field(f -> f.field("salesCount").order(SortOrder.Desc)))
			.source(src -> src.filter(f -> f.includes("name")))
			.size(limit));
	}

	Query buildQuery(String keyword, String productType, String sort) {
		List<Query> filters = new ArrayList<>();
		if (productType != null && !ALL_PRODUCT_TYPES.equals(productType)) {
			filters.add(Query.of(q -> q.term(t -> t.field("productType").value(productType))));
		}

		Query base = (keyword == null || keyword.isBlank())
			? Query.of(q -> q.matchAll(m -> m))
			: Query.of(q -> q.multiMatch(m -> m
				.query(keyword)
				.type(TextQueryType.BestFields)
				.tieBreaker(0.3)
				.fields(MATCH_FIELDS)));

		Query filtered = Query.of(q -> q.bool(b -> b.must(base).filter(filters)));

		return SORT_POPULAR.equals(sort) ? withPopularityBoost(filtered) : filtered;
	}

	private Query withPopularityBoost(Query base) {
		List<FunctionScore> functions = List.of(
			FunctionScore.of(fn -> fn
				.weight(rankingProperties.salesWeight())
				.fieldValueFactor(fv -> fv.field("salesCount").modifier(FieldValueFactorModifier.Log1p))),
			FunctionScore.of(fn -> fn
				.weight(rankingProperties.viewWeight())
				.fieldValueFactor(fv -> fv.field("viewCount").modifier(FieldValueFactorModifier.Log1p))),
			FunctionScore.of(fn -> fn
				.weight(rankingProperties.ratingWeight())
				.fieldValueFactor(fv -> fv.field("ratingAvg").missing(0.0))),
			FunctionScore.of(fn -> fn
				.weight(rankingProperties.freshnessWeight())
				.gauss(g -> g.date(d -> d
					.field("firstPublishedAt")
					.placement(p -> p
						.scale(Time.of(t -> t.time(rankingProperties.freshnessScale())))
						.decay(rankingProperties.freshnessDecay())))))
		);

		return Query.of(q -> q.functionScore(fs -> fs
			.query(base)
			.functions(functions)
			.scoreMode(FunctionScoreMode.Sum)
			.boostMode(FunctionBoostMode.Sum)));
	}

	List<SortOptions> buildSort(String sort) {
		SortOptions tiebreaker = SortOptions.of(so -> so.field(f -> f.field("familyRootId").order(SortOrder.Asc)));
		return switch (sort) {
			case SORT_RATING -> List.of(
				SortOptions.of(so -> so.field(f -> f.field("ratingAvg").order(SortOrder.Desc))),
				tiebreaker);
			case SORT_PRICE_ASC -> List.of(
				SortOptions.of(so -> so.field(f -> f.field("amount").order(SortOrder.Asc))),
				tiebreaker);
			default -> List.of(
				SortOptions.of(so -> so.score(sc -> sc.order(SortOrder.Desc))),
				tiebreaker);
		};
	}
}
