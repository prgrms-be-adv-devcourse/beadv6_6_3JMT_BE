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
class SettlementBatchRestartMigrationIntegrationTest {

    private static final UUID LINKED_BATCH_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_JOB_BATCH_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID UNMATCHED_BATCH_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("settlement")
            .withUsername("settlement")
            .withPassword("settlement");

    @Test
    @DisplayName("연결 가능한 레거시 배치만 보정하고 재시작 메타데이터 제약을 추가한다")
    void migrate_backfillsOnlyUnambiguousSettlementJobBatch() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();
        insertJobMetadata(jdbc, 11L, 101L, "settlementJob", "linked-job-key");
        insertJobMetadata(jdbc, 12L, 102L, "otherJob", "other-job-key");
        insertLegacyBatch(
                jdbc,
                LINKED_BATCH_ID,
                "SETTLE-20260713-20260719-SCHEDULED-101");
        insertLegacyBatch(
                jdbc,
                OTHER_JOB_BATCH_ID,
                "SETTLE-20260713-20260719-SCHEDULED-102");
        insertLegacyBatch(jdbc, UNMATCHED_BATCH_ID, "LEGACY-BATCH-NO");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        assertThat(jobInstanceId(jdbc, LINKED_BATCH_ID)).isEqualTo(11L);
        assertThat(jobInstanceId(jdbc, OTHER_JOB_BATCH_ID)).isNull();
        assertThat(jobInstanceId(jdbc, UNMATCHED_BATCH_ID)).isNull();
        assertThat(rowCount(jdbc)).isEqualTo(3);
        assertThat(version(jdbc, LINKED_BATCH_ID)).isZero();

        jdbc.update(
                "update settlement_batch set status = 'RETRY_REQUESTED' where batch_id = ?",
                LINKED_BATCH_ID);
        assertThat(status(jdbc, LINKED_BATCH_ID)).isEqualTo("RETRY_REQUESTED");

        assertThatThrownBy(() -> jdbc.update("""
                        insert into settlement_batch (
                            batch_id,
                            batch_no,
                            job_instance_id,
                            period_start,
                            period_end,
                            status,
                            trigger_type
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                "DUPLICATE-JOB-INSTANCE",
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                "FAILED",
                "SCHEDULED"))
                .isInstanceOf(DataAccessException.class);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private void insertJobMetadata(
            JdbcTemplate jdbc,
            long jobInstanceId,
            long jobExecutionId,
            String jobName,
            String jobKey) {
        jdbc.update("""
                        insert into batch_job_instance (
                            job_instance_id, version, job_name, job_key
                        ) values (?, ?, ?, ?)
                        """,
                jobInstanceId,
                0L,
                jobName,
                jobKey);
        jdbc.update("""
                        insert into batch_job_execution (
                            job_execution_id, version, job_instance_id, create_time, status
                        ) values (?, ?, ?, current_timestamp, ?)
                        """,
                jobExecutionId,
                0L,
                jobInstanceId,
                "FAILED");
    }

    private void insertLegacyBatch(JdbcTemplate jdbc, UUID batchId, String batchNo) {
        jdbc.update("""
                        insert into settlement_batch (
                            batch_id, batch_no, period_start, period_end, status, trigger_type
                        ) values (?, ?, ?, ?, ?, ?)
                        """,
                batchId,
                batchNo,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                "FAILED",
                "SCHEDULED");
    }

    private Long jobInstanceId(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject(
                "select job_instance_id from settlement_batch where batch_id = ?",
                Long.class,
                batchId);
    }

    private long version(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject(
                "select version from settlement_batch where batch_id = ?",
                Long.class,
                batchId);
    }

    private String status(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject(
                "select status from settlement_batch where batch_id = ?",
                String.class,
                batchId);
    }

    private int rowCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from settlement_batch", Integer.class);
    }
}
