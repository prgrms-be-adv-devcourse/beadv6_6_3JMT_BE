package com.prompthub.order.infra.persistence;

import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryRecoveryMigrationTest extends PostgreSqlIntegrationTestSupport {

    private static final UUID PENDING_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID FAILED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private OrderExpirationStore orderExpirationStore;

    @Test
    void migrationAddsRetryRecoverySchemaAndPreservesLegacyOutboxStates() {
        String schema = "outbox_retry_recovery_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("create schema " + schema);
        try {
            DriverManagerDataSource dataSource = schemaDataSource(schema);
            JdbcTemplate template = new JdbcTemplate(dataSource);
            migrateToVersion(dataSource, "7");
            insertLegacyOutboxEvent(template, PENDING_EVENT_ID, "PENDING");
            insertLegacyOutboxEvent(template, FAILED_EVENT_ID, "FAILED");

            migrateLatest(dataSource);

            assertThat(columns(template, "order_outbox_event"))
                .contains("next_attempt_at", "last_attempt_at", "last_error", "lease_owner", "lease_until");
            assertThat(template.queryForObject(
                "select next_attempt_at = occurred_at from order_outbox_event where event_id = ?",
                Boolean.class,
                PENDING_EVENT_ID
            )).isTrue();
            assertThat(indexDefinition(template, "idx_order_outbox_event_publishable"))
                .contains("(status, next_attempt_at, lease_until, occurred_at)");
            assertThat(status(template, FAILED_EVENT_ID)).isEqualTo("FAILED");
            assertThat(nextAttemptAt(template, FAILED_EVENT_ID)).isNull();
        } finally {
            jdbcTemplate.execute("drop schema " + schema + " cascade");
        }
    }

    private void migrateToVersion(DriverManagerDataSource dataSource, String version) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion(version))
            .load()
            .migrate();
    }

    private void migrateLatest(DriverManagerDataSource dataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    private void insertLegacyOutboxEvent(JdbcTemplate template, UUID eventId, String status) {
        template.update("""
            insert into order_outbox_event
                (event_id, aggregate_id, event_type, payload, status, retry_count, occurred_at, published_at)
            values (?, ?, 'ORDER_PAID', '{}', ?, 3, ?, null)
            """, eventId, AGGREGATE_ID, status, Timestamp.valueOf(OCCURRED_AT));
    }

    private List<String> columns(JdbcTemplate template, String tableName) {
        return template.queryForList("""
            select column_name
            from information_schema.columns
            where table_schema = current_schema()
              and table_name = ?
            """, String.class, tableName);
    }

    private String status(JdbcTemplate template, UUID eventId) {
        return template.queryForObject(
            "select status from order_outbox_event where event_id = ?",
            String.class,
            eventId
        );
    }

    private LocalDateTime nextAttemptAt(JdbcTemplate template, UUID eventId) {
        return template.queryForObject(
            "select next_attempt_at from order_outbox_event where event_id = ?",
            LocalDateTime.class,
            eventId
        );
    }

    private String indexDefinition(JdbcTemplate template, String indexName) {
        return template.queryForObject("""
            select indexdef
            from pg_indexes
            where schemaname = current_schema()
              and indexname = ?
            """, String.class, indexName);
    }

    private DriverManagerDataSource schemaDataSource(String schema) {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String parameterSeparator = jdbcUrl.contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
            jdbcUrl + parameterSeparator + "currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }
}
