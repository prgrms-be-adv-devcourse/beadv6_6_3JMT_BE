package com.prompthub.settlement.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SettlementSourceReconciliationMigrationIntegrationTest {

    private static final UUID BATCH_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement");

    @Test
    @DisplayName("V6은 settlement_batch에 원천 대사 실패 상태를 허용한다")
    void migrate_allowsReconciliationFailedBatchStatus() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();

        assertThatThrownBy(() -> insertReconciliationFailedBatch(jdbc))
                .isInstanceOf(DataAccessException.class);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();

        insertReconciliationFailedBatch(jdbc);

        assertThat(jdbc.queryForObject(
                "select status from settlement_batch where batch_id = ?",
                String.class,
                BATCH_ID))
                .isEqualTo("RECONCILIATION_FAILED");
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private void insertReconciliationFailedBatch(JdbcTemplate jdbc) {
        jdbc.update("""
                        insert into settlement_batch (
                            batch_id,
                            batch_no,
                            period_start,
                            period_end,
                            status,
                            trigger_type
                        ) values (?, ?, ?, ?, ?, ?)
                        """,
                BATCH_ID,
                "SETTLE-RECONCILIATION-FAILED",
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 26),
                "RECONCILIATION_FAILED",
                "SCHEDULED");
    }
}
