package com.prompthub.product.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * DB를 쓰는 테스트는 H2가 아니라 운영과 같은 Postgres에서 돈다.
 *
 * <p>H2는 {@code MODE=PostgreSQL}이어도 진짜 Postgres가 아니다. 스키마를 Hibernate가
 * 엔티티에서 만들어내므로 실제 Flyway 마이그레이션과 어긋나도 테스트는 통과한다.
 * 여기서는 마이그레이션을 그대로 실행해 테스트 스키마와 운영 스키마를 같게 만든다.
 *
 * <p>이미지는 아직 순정이다. #378이 마이그레이션에 {@code CREATE EXTENSION vector}를
 * 넣는 시점에 pgvector 포함 이미지로 바꾼다(태그 한 줄).
 */
public abstract class PostgresIntegrationTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

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
