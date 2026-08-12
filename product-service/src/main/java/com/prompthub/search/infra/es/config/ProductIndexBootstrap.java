package com.prompthub.search.infra.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * alias·index·mapping 상태를 점검하고 필요할 때만 만든다. rogue index가 alias 이름을 점유하거나
 * alias가 기대와 다른 index를 가리키는 상태는 실제로 겪은 장애라, 자동으로 덮어쓰거나 재연결하지
 * 않고 시작을 막아 사람이 확인하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexBootstrap {

	public static final String ALIAS = "products";
	private static final String INDEX = "products-v1";
	private static final String MAPPING_RESOURCE = "es/products-v1-mapping.json";

	/** 검색·색인에 실제로 쓰는 핵심 field만 검증한다 — 매핑 전체를 구조적으로 비교하지 않는다. */
	private static final Map<String, Property.Kind> REQUIRED_FIELD_KINDS = Map.of(
		"familyRootId", Property.Kind.Keyword,
		"productId", Property.Kind.Keyword,
		"amount", Property.Kind.Integer,
		"embedding", Property.Kind.DenseVector
	);
	private static final int EMBEDDING_DIMENSIONS = 1536;

	private final ElasticsearchClient client;

	@EventListener(ApplicationReadyEvent.class)
	public void createIndexIfMissing() throws IOException {
		if (client.indices().existsAlias(e -> e.name(ALIAS)).value()) {
			validateAliasTarget();
			validateMapping();
			log.info("alias={} index={} 정상 확인.", ALIAS, INDEX);
			return;
		}

		failIfAliasNameTakenByRealIndex();

		if (client.indices().exists(e -> e.index(INDEX)).value()) {
			validateMapping();
			client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
			log.info("alias={} 재연결 완료. index={}", ALIAS, INDEX);
			return;
		}

		createIndexAndAttachAlias();
	}

	/** {@code products} 이름이 alias가 아니라 실제 index로 점유된 상태 — 겪었던 alias 충돌 장애와 같은 패턴이다. */
	private void failIfAliasNameTakenByRealIndex() throws IOException {
		if (client.indices().exists(e -> e.index(ALIAS)).value()) {
			throw new IllegalStateException("index=" + ALIAS + "가 실제 index로 존재해 alias 이름을 점유하고 있습니다. "
				+ "그 index를 다른 이름으로 재색인하거나 삭제한 뒤 재기동하세요.");
		}
	}

	private void validateAliasTarget() throws IOException {
		Set<String> aliasedIndexes = client.indices().getAlias(a -> a.name(ALIAS)).aliases().keySet();
		if (!aliasedIndexes.equals(Set.of(INDEX))) {
			throw new IllegalStateException("alias=" + ALIAS + "가 기대하지 않는 index를 가리킵니다. "
				+ "actual=" + aliasedIndexes + ", expected=[" + INDEX + "]. 수동으로 alias를 재연결한 뒤 재기동하세요.");
		}
	}

	private void validateMapping() throws IOException {
		TypeMapping mapping = client.indices().getMapping(g -> g.index(INDEX)).get(INDEX).mappings();
		Map<String, Property> properties = mapping.properties();

		for (Map.Entry<String, Property.Kind> required : REQUIRED_FIELD_KINDS.entrySet()) {
			Property property = properties.get(required.getKey());
			Property.Kind actualKind = property == null ? null : property._kind();
			if (actualKind != required.getValue()) {
				throw new IllegalStateException("ES index mapping이 기대와 다릅니다. index=" + INDEX
					+ ", field=" + required.getKey() + ", expected=" + required.getValue() + ", actual=" + actualKind);
			}
		}

		Integer actualDims = properties.get("embedding").denseVector().dims();
		if (!Integer.valueOf(EMBEDDING_DIMENSIONS).equals(actualDims)) {
			throw new IllegalStateException("ES embedding dense_vector dims가 기대와 다릅니다. index=" + INDEX
				+ ", expected=" + EMBEDDING_DIMENSIONS + ", actual=" + actualDims);
		}
	}

	private void createIndexAndAttachAlias() throws IOException {
		try (InputStream mapping = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
			client.indices().create(c -> c.index(INDEX).withJson(mapping));
		}
		client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
		log.info("index={} 생성 및 alias={} 연결 완료", INDEX, ALIAS);
	}
}
