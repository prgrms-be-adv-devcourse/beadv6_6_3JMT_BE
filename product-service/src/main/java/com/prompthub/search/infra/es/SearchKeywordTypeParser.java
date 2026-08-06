package com.prompthub.search.infra.es;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 검색어 속 상품 유형 단어를 productType 필터로 해석한다.
 *
 * <p>"주식 프롬프트"라고 치는 사용자의 의도는 "프롬프트라는 글자가 들어간 상품"이 아니라
 * "프롬프트 유형 중 주식 관련"이다. 유형 단어를 글자로 매칭하면 그 단어를 이름에 단 모든
 * 상품이 꼬리로 딸려오고(#689), 질의 임베딩도 유형 쪽으로 쏠려 의미 레그까지 오염된다
 * (dev 실측: "주식 프롬프트"의 kNN 1위가 자기소개서 첨삭, 코사인 0.435). 유형 단어는
 * 필터로 옮기고 남은 단어로만 글자·의미 두 레그를 돌린다.
 *
 * <p>"주식프롬프트"처럼 붙여 쓴 경우도 접미사로 해석한다. 유형 단어만 남으면 검색어가
 * 빈 문자열이 되어 기존 "빈 검색어 + 유형 필터" 경로(match_all)로 그 유형 전체가 나온다.
 *
 * <p>사용자가 화면에서 유형을 이미 골랐으면({@code productType != all}) 그 선택을 존중하고
 * 검색어를 해석하지 않는다.
 */
final class SearchKeywordTypeParser {

	record Parsed(String keyword, String productType) {
	}

	private static final Map<String, String> TYPE_WORDS = Map.of(
		"프롬프트", "PROMPT", "prompt", "PROMPT",
		"노션", "NOTION", "notion", "NOTION",
		"엑셀", "EXCEL", "excel", "EXCEL",
		"피피티", "PPT", "ppt", "PPT");

	private static final String ALL_PRODUCT_TYPES = "all";

	private SearchKeywordTypeParser() {
	}

	static Parsed parse(String keyword, String productType) {
		boolean explicitType = productType != null && !ALL_PRODUCT_TYPES.equals(productType);
		if (explicitType || keyword == null || keyword.isBlank()) {
			return new Parsed(keyword, productType);
		}

		String parsedType = null;
		List<String> remaining = new ArrayList<>();
		for (String token : keyword.trim().split("\\s+")) {
			String lower = token.toLowerCase(Locale.ROOT);
			String exactType = TYPE_WORDS.get(lower);
			if (exactType != null) {
				parsedType = firstWins(parsedType, exactType);
				continue;
			}
			String suffix = matchedSuffix(lower);
			if (suffix != null) {
				parsedType = firstWins(parsedType, TYPE_WORDS.get(suffix));
				remaining.add(token.substring(0, token.length() - suffix.length()));
				continue;
			}
			remaining.add(token);
		}

		if (parsedType == null) {
			return new Parsed(keyword, productType);
		}
		return new Parsed(String.join(" ", remaining), parsedType);
	}

	/** 유형 단어가 여러 개면 첫 단어를 따른다. ("노션 프롬프트" 같은 모호한 질의는 드물다) */
	private static String firstWins(String current, String candidate) {
		return current != null ? current : candidate;
	}

	private static String matchedSuffix(String lowerToken) {
		for (String word : TYPE_WORDS.keySet()) {
			if (lowerToken.length() > word.length() && lowerToken.endsWith(word)) {
				return word;
			}
		}
		return null;
	}
}
