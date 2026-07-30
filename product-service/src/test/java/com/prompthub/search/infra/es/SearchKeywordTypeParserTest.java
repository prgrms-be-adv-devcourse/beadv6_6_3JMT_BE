package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchKeywordTypeParserTest {

	@Test
	void 유형_단어는_필터로_옮기고_남은_단어만_검색어로_남긴다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("주식 프롬프트", "all");

		assertThat(parsed.keyword()).isEqualTo("주식");
		assertThat(parsed.productType()).isEqualTo("PROMPT");
	}

	@Test
	void 유형_단어만_치면_검색어가_비고_유형_필터만_남는다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("프롬프트", "all");

		assertThat(parsed.keyword()).isEmpty();
		assertThat(parsed.productType()).isEqualTo("PROMPT");
	}

	@Test
	void 붙여_쓴_유형_접미사도_해석한다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("주식프롬프트", "all");

		assertThat(parsed.keyword()).isEqualTo("주식");
		assertThat(parsed.productType()).isEqualTo("PROMPT");
	}

	@Test
	void 영문_유형_단어도_대소문자_무관하게_해석한다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("가계부 Excel", "all");

		assertThat(parsed.keyword()).isEqualTo("가계부");
		assertThat(parsed.productType()).isEqualTo("EXCEL");
	}

	@Test
	void 유형_단어가_없으면_그대로_둔다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("주식", "all");

		assertThat(parsed.keyword()).isEqualTo("주식");
		assertThat(parsed.productType()).isEqualTo("all");
	}

	@Test
	void 화면에서_유형을_이미_골랐으면_검색어를_해석하지_않는다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("주식 프롬프트", "NOTION");

		assertThat(parsed.keyword()).isEqualTo("주식 프롬프트");
		assertThat(parsed.productType()).isEqualTo("NOTION");
	}

	@Test
	void 유형_단어가_여러_개면_첫_단어를_따른다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("노션 프롬프트", "all");

		assertThat(parsed.keyword()).isEmpty();
		assertThat(parsed.productType()).isEqualTo("NOTION");
	}

	@Test
	void 빈_검색어는_그대로_둔다() {
		SearchKeywordTypeParser.Parsed parsed = SearchKeywordTypeParser.parse("", "all");

		assertThat(parsed.keyword()).isEmpty();
		assertThat(parsed.productType()).isEqualTo("all");
	}
}
