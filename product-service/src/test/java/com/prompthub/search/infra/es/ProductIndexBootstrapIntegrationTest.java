package com.prompthub.search.infra.es;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.search.support.ElasticsearchIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProductIndexBootstrapIntegrationTest extends ElasticsearchIntegrationTestSupport {

	@Autowired
	private ElasticsearchClient client;

	@Test
	void createIndexIfMissing_alias와_nori_분석기가_준비된다() throws Exception {
		boolean aliasExists = client.indices().existsAlias(e -> e.name(ProductIndexBootstrap.ALIAS)).value();
		assertThat(aliasExists).isTrue();

		var analyzeResponse = client.indices().analyze(a -> a
			.index("products-v1")
			.analyzer("korean")
			.text("프롬프트"));
		assertThat(analyzeResponse.tokens()).isNotEmpty();
	}
}
