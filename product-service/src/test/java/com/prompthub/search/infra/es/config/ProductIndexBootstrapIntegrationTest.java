package com.prompthub.search.infra.es.config;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProductIndexBootstrapIntegrationTest extends ElasticsearchIntegrationTestSupport {

	@Autowired
	private ElasticsearchClient client;

	@Test
	void createIndexIfMissing_alias와_name필드_분석이_준비된다() throws Exception {
		boolean aliasExists = client.indices().existsAlias(e -> e.name(ProductIndexBootstrap.ALIAS)).value();
		assertThat(aliasExists).isTrue();

		// 분석기 이름 대신 field로 확인한다 — 매핑이 어떤 분석기를 쓰든 name 필드가
		// 한글을 토큰으로 쪼갤 수 있으면 색인이 동작한다.
		var analyzeResponse = client.indices().analyze(a -> a
			.index("products-v1")
			.field("name")
			.text("회의록 정리 노션 템플릿"));
		assertThat(analyzeResponse.tokens()).isNotEmpty();
	}
}
