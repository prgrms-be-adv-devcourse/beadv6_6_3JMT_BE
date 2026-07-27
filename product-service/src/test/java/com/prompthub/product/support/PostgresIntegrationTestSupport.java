package com.prompthub.product.support;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * DB를 쓰는 테스트는 H2가 아니라 운영과 같은 Postgres에서 돈다.
 *
 * <p>H2는 {@code MODE=PostgreSQL}이어도 진짜 Postgres가 아니다. 스키마를 Hibernate가
 * 엔티티에서 만들어내므로 실제 Flyway 마이그레이션과 어긋나도 테스트는 통과한다.
 * 여기서는 마이그레이션을 그대로 실행해 테스트 스키마와 운영 스키마를 같게 만든다.
 *
 * <p>이미지는 운영과 같은 pgvector 포함 커스텀 이미지다. V3 마이그레이션이
 * {@code CREATE EXTENSION vector}를 실행하므로 순정 이미지로는 뜨지 않는다.
 * GHCR에 아직 퍼블리시되지 않았으므로 {@code docker/postgres/Dockerfile}에서 직접 빌드한다
 * — 레지스트리 의존 없이 CI에서도 같은 이미지가 만들어진다.
 */
public abstract class PostgresIntegrationTestSupport {

	// deleteOnExit=false — 매 실행마다 다시 빌드하지 않게 이미지를 남겨둔다.
	private static final String IMAGE = new ImageFromDockerfile("prompthub-postgres-pgvector-test", false)
		.withDockerfile(Path.of("../docker/postgres/Dockerfile"))
		.get();

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=product_service");
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
	}
}
