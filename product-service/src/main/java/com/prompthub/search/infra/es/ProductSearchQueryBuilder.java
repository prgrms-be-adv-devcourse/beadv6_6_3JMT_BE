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

	public SearchRequest build(String keyword, String productType, String sort, int page, int size) {
		int from = Math.max(page - 1, 0) * size;
		Query query = buildQuery(keyword, productType, sort);
		List<SortOptions> sortOptions = buildSort(sort);

		return SearchRequest.of(s -> s
			.index(ProductIndexBootstrap.ALIAS)
			.query(query)
			.sort(sortOptions)
			.from(from)
			.size(size)
			.trackTotalHits(t -> t.enabled(true)));
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
