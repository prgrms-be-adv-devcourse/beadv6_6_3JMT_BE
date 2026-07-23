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
class SettlementInitialStatusDefaultsMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement");

    @Test
    @DisplayName("초기 지급과 정산 상태의 DB 기본값을 추가한다")
    void migrate_addsInitialStatusDefaults() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        JdbcTemplate jdbc = jdbcTemplate();

        assertThat(columnDefault(jdbc, "payout_status")).contains("NOT_READY");
        assertThat(columnDefault(jdbc, "settlement_status")).contains("PENDING_APPROVAL");
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private String columnDefault(JdbcTemplate jdbc, String columnName) {
        return jdbc.queryForObject("""
                        select column_default
                        from information_schema.columns
                        where table_schema = current_schema()
                          and table_name = 'settlement'
                          and column_name = ?
                        """,
                String.class,
                columnName);
    }
}
