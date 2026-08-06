package com.prompthub.order.support;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.kafka.listener.auto-startup=false",
		"spring.cloud.config.enabled=false",
		"spring.cloud.discovery.enabled=false",
		"eureka.client.enabled=false",
		"prompthub.outbox-relay.enabled=false",
		"prompthub.order.enabled=false"
	}
)
@ActiveProfiles("test")
public abstract class PostgreSqlIntegrationTestSupport {

	protected static final PostgreSQLContainer POSTGRES;

	@Autowired
	private JdbcTemplate databaseCleanupJdbcTemplate;

	@MockitoBean
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	static {
		POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"))
			.withDatabaseName("order_service_test");
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void postgresProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@BeforeEach
	@AfterEach
	void cleanApplicationData() {
		List<String> tables = databaseCleanupJdbcTemplate.queryForList("""
			select format('%I.%I', table_schema, table_name)
			from information_schema.tables
			where table_schema = 'public'
			  and table_type = 'BASE TABLE'
			  and table_name <> 'flyway_schema_history'
			""", String.class);
		if (!tables.isEmpty()) {
			databaseCleanupJdbcTemplate.execute(
				"truncate table " + String.join(", ", tables) + " restart identity cascade"
			);
		}
	}
}
