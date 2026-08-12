package com.prompthub.search.support;

import com.prompthub.search.infra.es.config.ElasticsearchClientConfig;
import com.prompthub.search.infra.es.config.ProductIndexBootstrap;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * org.testcontainers:elasticsearch 전용 모듈이 testcontainers-core 2.x 라인에 맞는
 * 릴리스가 아직 없어(#376 Task 5 참고) GenericContainer로 직접 띄운다.
 *
 * 이미지는 쿠버네티스 elk 네임스페이스가 쓰는 것과 같은 순정 이미지다(#379) — 플러그인이
 * 필요 없는 매핑이라 커스텀 이미지를 빌드하지 않는다. 테스트 환경과 운영 환경의 분석기가
 * 같아야 여기서 검증한 검색 동작이 운영에서도 성립한다.
 *
 * classes를 ES 관련 빈으로 한정한다 — 기본 부트스트랩 클래스(ProductApplication) 전체를
 * 띄우면 이 테스트와 무관한 S3Config 등이 함께 로드돼 AWS 자격증명 부재로 실패한다.
 */
@SpringBootTest(classes = {ElasticsearchClientConfig.class, ProductIndexBootstrap.class})
public abstract class ElasticsearchIntegrationTestSupport {

	private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
		"docker.elastic.co/elasticsearch/elasticsearch:9.4.3")
		.withEnv("discovery.type", "single-node")
		.withEnv("xpack.security.enabled", "false")
		.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
		.withExposedPorts(9200)
		.waitingFor(Wait.forHttp("/").forStatusCode(200));

	static {
		ELASTICSEARCH.start();
	}

	@DynamicPropertySource
	static void esProperties(DynamicPropertyRegistry registry) {
		registry.add("elasticsearch.uris", () ->
			"http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
	}
}
