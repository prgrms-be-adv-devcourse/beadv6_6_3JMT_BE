package com.prompthub.settlement.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SettlementLedgerColumnsMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement");

    @Test
    @DisplayName("계산 원장에는 운영 지급과 정산 상태 컬럼을 남기지 않는다")
    void migrate_removesOperationalStatusColumnsFromSettlementLedger() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("4"))
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();

        assertThat(columnExists(jdbc, "payout_status")).isTrue();
        assertThat(columnExists(jdbc, "settlement_status")).isTrue();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        assertThat(columnExists(jdbc, "payout_status")).isFalse();
        assertThat(columnExists(jdbc, "settlement_status")).isFalse();
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private boolean columnExists(JdbcTemplate jdbc, String columnName) {
        return jdbc.queryForObject("""
                        select exists (
                            select 1
                            from information_schema.columns
                            where table_schema = current_schema()
                              and table_name = 'settlement'
                              and column_name = ?
                        )
                        """,
                Boolean.class,
                columnName);
    }
}
