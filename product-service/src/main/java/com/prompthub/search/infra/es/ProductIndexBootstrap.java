package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexBootstrap {

	public static final String ALIAS = "products";
	private static final String INDEX = "products-v1";
	private static final String MAPPING_RESOURCE = "es/products-v1-mapping.json";

	private final ElasticsearchClient client;

	@EventListener(ApplicationReadyEvent.class)
	public void createIndexIfMissing() throws IOException {
		if (client.indices().existsAlias(e -> e.name(ALIAS)).value()) {
			log.info("alias={} 이미 존재합니다. 인덱스 생성을 건너뜁니다.", ALIAS);
			return;
		}

		try (InputStream mapping = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
			client.indices().create(c -> c.index(INDEX).withJson(mapping));
		}
		client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
		log.info("index={} 생성 및 alias={} 연결 완료", INDEX, ALIAS);
	}
}
