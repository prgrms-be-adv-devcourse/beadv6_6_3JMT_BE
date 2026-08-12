package com.prompthub.search.infra.es.query;

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
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import com.prompthub.search.infra.es.config.SearchRankingProperties;
import org.springframework.stereotype.Component;

/**
 * ES 검색 요청을 순수하게 구성한다(I/O 없음 — ES 없이 유닛 테스트 가능).
 * 실행은 {@link ElasticsearchProductSearchQuerier}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class ProductSearchQueryBuilder {

	/**
	 * {@code content}(프롬프트 본문)는 검색하지 않는다. 본문은 "예: 삼성전자, Apple" 같은
	 * placeholder 예시로 가득해, 상품과 무관한 검색어("주식")가 예시 문구에 걸려 오탐을 만든다
	 * (#689 — dev 실측으로 확인). 상품이 무엇인지는 이름·태그·설명이 이미 담고 있다.
	 *
	 * <p>{@code model.text}는 최하 가중치로 검색한다(#699). 화면에 모델 뱃지("GPT-5.6")가
	 * 보이는데 "gpt" 검색이 0건이면 사용자에겐 고장이다. 대부분의 상품이 GPT 계열 모델이라
	 * 가중치를 최하로 두어, 이름·태그·설명에 걸린 상품이 항상 모델만 걸린 상품보다 앞선다.
	 */
	private static final List<String> MATCH_FIELDS = List.of("name^3", "tags.text^2", "description^1.5", "model.text");

	/**
	 * 여러 단어 검색에서 2단어면 모두, 3단어 이상이면 75% 이상 일치해야 매칭한다.
	 *
	 * <p>기본값(OR)이면 단어 하나만 겹쳐도 매칭되는데, 이 코퍼스는 대부분의 상품명이
	 * "프롬프트"를 포함해 그 단어 하나가 만능 열쇠가 된다 — "주식 프롬프트" 검색에 "펭귄 생성
	 * 프롬프트"까지 꼬리로 딸려온다(#689). 한 단어 검색은 영향이 없다.
	 */
	private static final String MINIMUM_SHOULD_MATCH = "2<75%";
	private static final String ALL_PRODUCT_TYPES = "all";
	static final String SORT_POPULAR = "popular";
	private static final String SORT_RATING = "rating";
	private static final String SORT_PRICE_ASC = "price-asc";

	/**
	 * 의미 기반 레그가 문서를 후보로 올리는 코사인 유사도 하한.
	 *
	 * <p>{@code products-v1} 매핑이 {@code similarity: cosine}이라 이 값은 코사인 그대로다.
	 * 설정으로 빼지 않는다 — {@code configs/}가 config server 이미지에 구워져 yml을 고쳐도
	 * 머지·재배포가 필요해서 노브로 만들어도 조정이 빨라지지 않는다({@code TYPE_BONUS}와 같은 이유).
	 *
	 * <p>2026-07-30 dev 실측(색인 36건)으로 유효성을 확인했다(#689). 무관 질의 5종의 최고
	 * 코사인은 0.249~0.305로 전부 하한 미달(오탐 0건), 명확히 관련된 질의는 0.435~0.494로
	 * 통과했다. 하한에 걸리는 관련 질의("면접 준비" 0.325 등)는 글자 레그가 잡아준다.
	 * 조정 신호는 두 방향 모두 검색 결과로 드러난다 — 무관한 질의에 결과가 남으면 올리고,
	 * 글자가 안 겹치는데 의미로 찾아야 할 상품이 안 나오면 내린다.
	 */
	private static final float MIN_SEMANTIC_SIMILARITY = 0.35f;

	private final SearchRankingProperties rankingProperties;

	public SearchRequest build(String keyword, String productType, String sort, Pageable pageable) {
		return build(keyword, productType, sort, (int) pageable.getOffset(), pageable.getPageSize());
	}

	/**
	 * 하이브리드 검색은 페이지가 아니라 병합용 창(상위 N건)을 받아야 해서 from·size를 직접 받는다.
	 */
	public SearchRequest build(String keyword, String productType, String sort, int from, int size) {
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

	/**
	 * 의미 기반 레그 요청을 구성한다. 글자 기반 레그와 <b>같은 필터</b>를 걸어야 한다 —
	 * 두 레그의 대상 집합이 다르면 병합 결과에 필터 밖 문서가 섞인다.
	 *
	 * <p>정렬을 걸지 않는다. kNN은 유사도 순으로 돌아오고 그 순서 자체가 이 레그의 순위다.
	 *
	 * <p>{@code similarity} 하한을 반드시 건다. kNN은 관련도와 무관하게 상위 {@code k}건을
	 * 채워 돌려주므로, 하한이 없으면 색인 문서가 {@code k}보다 적을 때 어떤 질의든 전체 문서가
	 * 후보로 들어와 검색 결과 0건이 발생할 수 없게 된다(#645).
	 */
	public SearchRequest buildKnn(float[] queryVector, String productType, int size) {
		List<Float> vector = new ArrayList<>(queryVector.length);
		for (float value : queryVector) {
			vector.add(value);
		}
		List<Query> filters = buildFilters(productType);

		return SearchRequest.of(s -> s
			.index(ProductIndexBootstrap.ALIAS)
			.knn(k -> k
				.field("embedding")
				.queryVector(vector)
				.k(size)
				// 후보를 넉넉히 훑어야 근사 탐색(HNSW)이 상위 k를 놓치지 않는다.
				.numCandidates(size * 2)
				.similarity(MIN_SEMANTIC_SIMILARITY)
				.filter(filters))
			.size(size));
	}

	private List<Query> buildFilters(String productType) {
		List<Query> filters = new ArrayList<>();
		if (productType != null && !ALL_PRODUCT_TYPES.equals(productType)) {
			filters.add(Query.of(q -> q.term(t -> t.field("productType").value(productType))));
		}
		return filters;
	}

	Query buildQuery(String keyword, String productType, String sort) {
		List<Query> filters = buildFilters(productType);

		Query base = (keyword == null || keyword.isBlank())
			? Query.of(q -> q.matchAll(m -> m))
			: Query.of(q -> q.multiMatch(m -> m
				.query(keyword)
				.type(TextQueryType.BestFields)
				.tieBreaker(0.3)
				.minimumShouldMatch(MINIMUM_SHOULD_MATCH)
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
