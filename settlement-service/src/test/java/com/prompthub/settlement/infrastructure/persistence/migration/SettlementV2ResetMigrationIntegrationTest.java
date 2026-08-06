package com.prompthub.settlement.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SettlementV2ResetMigrationIntegrationTest {

    private static final UUID BATCH_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement");

    @BeforeEach
    void cleanDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    @DisplayName("V4는 정산과 Batch 실행 데이터를 지우고 sequence를 다시 시작한다")
    void migrate_resetsSettlementAndBatchData() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();
        insertBatchMetadata(jdbc);
        insertSettlementData(jdbc);
        jdbc.queryForObject("select setval('batch_job_instance_seq', 50, true)", Long.class);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        assertThat(rowCount(jdbc, "settlement_detail")).isZero();
        assertThat(rowCount(jdbc, "settlement")).isZero();
        assertThat(rowCount(jdbc, "settlement_batch")).isZero();
        assertThat(rowCount(jdbc, "batch_step_execution")).isZero();
        assertThat(rowCount(jdbc, "batch_job_execution")).isZero();
        assertThat(rowCount(jdbc, "batch_job_instance")).isZero();
        assertThat(jdbc.queryForObject("select nextval('batch_job_instance_seq')", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("최신 migration 뒤에는 현재 Settlement JPA projection으로 저장할 수 있다")
    void migrate_allowsCurrentSettlementProjection() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();

        int inserted = jdbc.update("""
                        insert into settlement (
                            settlement_id, created_at, updated_at, calculated_at, fee_total_amount,
                            period_end, period_start, product_count, refund_amount, seller_id,
                            settlement_batch_id, settlement_total_amount, total_amount
                        ) values (?, current_timestamp, current_timestamp, current_timestamp, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                15,
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 13),
                1,
                0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                85,
                100);

        assertThat(inserted).isEqualTo(1);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private void insertBatchMetadata(JdbcTemplate jdbc) {
        jdbc.update("""
                        insert into batch_job_instance (
                            job_instance_id, version, job_name, job_key
                        ) values (?, ?, ?, ?)
                        """,
                11L,
                0L,
                "settlementJob",
                "settlement-job-key");
        jdbc.update("""
                        insert into batch_job_execution (
                            job_execution_id, version, job_instance_id, create_time, status
                        ) values (?, ?, ?, current_timestamp, ?)
                        """,
                101L,
                0L,
                11L,
                "FAILED");
        jdbc.update("""
                        insert into batch_step_execution (
                            step_execution_id, version, step_name, job_execution_id, create_time, status
                        ) values (?, ?, ?, ?, current_timestamp, ?)
                        """,
                1001L,
                0L,
                "settlementStep",
                101L,
                "FAILED");
    }

    private void insertSettlementData(JdbcTemplate jdbc) {
        jdbc.update("""
                        insert into settlement_batch (
                            batch_id, batch_no, job_instance_id, period_start, period_end, status, trigger_type
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                BATCH_ID,
                "SETTLE-20260713-20260719-SCHEDULED-101",
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                "FAILED",
                "SCHEDULED");
        jdbc.update("""
                        insert into settlement (
                            settlement_id, calculated_at, fee_total_amount, payout_status,
                            period_end, period_start, product_count, refund_amount, seller_id,
                            settlement_batch_id, settlement_status, settlement_total_amount, total_amount
                        ) values (?, current_timestamp, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                SETTLEMENT_ID,
                15,
                "NOT_READY",
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 13),
                1,
                0,
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                BATCH_ID,
                "PENDING_APPROVAL",
                85,
                100);
        jdbc.update("""
                        insert into settlement_detail (
                            settlement_detail_id, created_at, fee_amount, fee_rate, line_amount,
                            line_settlement_amount, line_type, occurred_at, order_product_id, settlement_id
                        ) values (?, current_timestamp, ?, ?, ?, ?, ?, current_timestamp, ?, ?)
                        """,
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                15,
                0.15,
                100,
                85,
                "SALE",
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                SETTLEMENT_ID);
    }

    private int rowCount(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
