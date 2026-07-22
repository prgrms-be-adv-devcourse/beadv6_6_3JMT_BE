package com.prompthub.product.search.support;

import com.prompthub.product.search.infra.es.ElasticsearchClientConfig;
import com.prompthub.product.search.infra.es.ProductIndexBootstrap;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * org.testcontainers:elasticsearch 전용 모듈이 testcontainers-core 2.x 라인에 맞는
 * 릴리스가 아직 없어(#376 Task 5 참고) GenericContainer로 직접 nori 커스텀 이미지를
 * 빌드해 띄운다. Dockerfile은 product-service/docker/elasticsearch/Dockerfile과 동일 소스.
 *
 * classes를 ES 관련 빈으로 한정한다 — 기본 부트스트랩 클래스(ProductApplication) 전체를
 * 띄우면 이 테스트와 무관한 S3Config 등이 함께 로드돼 AWS 자격증명 부재로 실패한다.
 */
@SpringBootTest(classes = {ElasticsearchClientConfig.class, ProductIndexBootstrap.class})
public abstract class ElasticsearchIntegrationTestSupport {

	private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
		new ImageFromDockerfile("product-elasticsearch-nori-test", false)
			.withDockerfile(Path.of("docker/elasticsearch/Dockerfile"))
	)
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
