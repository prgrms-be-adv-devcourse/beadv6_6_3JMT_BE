package com.prompthub.settlement.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class SettlementCalculationReconciliationMigrationIntegrationTest {

    private static final UUID BATCH_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID UNAMBIGUOUS_SOURCE_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID UNAMBIGUOUS_DETAIL_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID UNMATCHED_DETAIL_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID AMBIGUOUS_DETAIL_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID UNAMBIGUOUS_PRODUCT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID UNMATCHED_PRODUCT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID AMBIGUOUS_PRODUCT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final UUID SELLER_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final LocalDateTime OCCURRED_AT =
            LocalDateTime.of(2026, 7, 14, 10, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine")
                    .withDatabaseName("settlement")
                    .withUsername("settlement")
                    .withPassword("settlement");

    @Test
    @DisplayName("V7은 명확한 기존 상세만 source line에 연결하고 대사 제약과 이력 테이블을 추가한다")
    void migrate_backfillsOnlyUnambiguousLinksAndAddsReconciliationSchema() {
        migrateToV5();
        JdbcTemplate jdbc = jdbcTemplate();
        insertBatch(jdbc);
        insertSettlement(jdbc);
        insertSourceLine(jdbc, UNAMBIGUOUS_SOURCE_ID, UNAMBIGUOUS_PRODUCT_ID, "100.00");
        insertSourceLine(
                jdbc,
                UUID.fromString("30000000-0000-0000-0000-000000000002"),
                AMBIGUOUS_PRODUCT_ID,
                "300.00");
        insertSourceLine(
                jdbc,
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                AMBIGUOUS_PRODUCT_ID,
                "300.00");
        insertDetail(jdbc, UNAMBIGUOUS_DETAIL_ID, UNAMBIGUOUS_PRODUCT_ID, "100.00");
        insertDetail(jdbc, UNMATCHED_DETAIL_ID, UNMATCHED_PRODUCT_ID, "200.00");
        insertDetail(jdbc, AMBIGUOUS_DETAIL_ID, AMBIGUOUS_PRODUCT_ID, "300.00");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        assertThat(sourceLineId(jdbc, UNAMBIGUOUS_DETAIL_ID))
                .isEqualTo(UNAMBIGUOUS_SOURCE_ID);
        assertThat(sourceLineId(jdbc, UNMATCHED_DETAIL_ID)).isNull();
        assertThat(sourceLineId(jdbc, AMBIGUOUS_DETAIL_ID)).isNull();
        assertThat(constraintExists(jdbc, "fk_settlement_detail_source_line")).isTrue();
        assertThat(constraintExists(jdbc, "uk_settlement_detail_source_line")).isTrue();
        assertThat(tableExists(jdbc, "settlement_calculation_reconciliation")).isTrue();

        assertThatThrownBy(() -> insertDetailWithSourceLine(
                jdbc,
                UUID.fromString("40000000-0000-0000-0000-000000000011"),
                UNAMBIGUOUS_SOURCE_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertDetailWithSourceLine(
                jdbc,
                UUID.fromString("40000000-0000-0000-0000-000000000012"),
                UUID.fromString("99999999-0000-0000-0000-000000000001")))
                .isInstanceOf(DataAccessException.class);

        insertDetailWithSourceLine(
                jdbc,
                UUID.fromString("40000000-0000-0000-0000-000000000013"),
                null);
        insertDetailWithSourceLine(
                jdbc,
                UUID.fromString("40000000-0000-0000-0000-000000000014"),
                null);
    }

    private void migrateToV5() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    private void insertBatch(JdbcTemplate jdbc) {
        jdbc.update("""
                        insert into settlement_batch (
                            batch_id, batch_no, period_start, period_end,
                            status, trigger_type, version
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                BATCH_ID,
                "SETTLE-20260713-20260719-SCHEDULED-642",
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                "PROCESSING",
                "SCHEDULED",
                0L);
    }

    private void insertSettlement(JdbcTemplate jdbc) {
        jdbc.update("""
                        insert into settlement (
                            settlement_id, created_at, updated_at, calculated_at,
                            fee_total_amount, period_start, period_end, product_count,
                            refund_amount, seller_id, settlement_batch_id,
                            settlement_total_amount, total_amount
                        ) values (?, current_timestamp, current_timestamp, current_timestamp,
                                  ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                SETTLEMENT_ID,
                new BigDecimal("105.00"),
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                3,
                BigDecimal.ZERO,
                SELLER_ID,
                BATCH_ID,
                new BigDecimal("595.00"),
                new BigDecimal("700.00"));
    }

    private void insertSourceLine(
            JdbcTemplate jdbc,
            UUID sourceLineId,
            UUID orderProductId,
            String amount) {
        jdbc.update("""
                        insert into settlement_source_line (
                            settlement_source_line_id, created_at, updated_at, event_id,
                            line_type, line_amount, occurred_at, order_id,
                            order_product_id, seller_id, settlement_id
                        ) values (?, current_timestamp, current_timestamp, ?, 'PAID', ?, ?, ?, ?, ?, ?)
                        """,
                sourceLineId,
                UUID.randomUUID(),
                new BigDecimal(amount),
                OCCURRED_AT,
                UUID.randomUUID(),
                orderProductId,
                SELLER_ID,
                SETTLEMENT_ID);
    }

    private void insertDetail(
            JdbcTemplate jdbc,
            UUID detailId,
            UUID orderProductId,
            String amount) {
        BigDecimal lineAmount = new BigDecimal(amount);
        BigDecimal feeAmount = lineAmount.multiply(new BigDecimal("0.15"));
        jdbc.update("""
                        insert into settlement_detail (
                            settlement_detail_id, created_at, fee_amount, fee_rate,
                            line_amount, line_settlement_amount, line_type,
                            occurred_at, order_product_id, settlement_id
                        ) values (?, current_timestamp, ?, ?, ?, ?, 'SALE', ?, ?, ?)
                        """,
                detailId,
                feeAmount,
                new BigDecimal("0.15"),
                lineAmount,
                lineAmount.subtract(feeAmount),
                OCCURRED_AT,
                orderProductId,
                SETTLEMENT_ID);
    }

    private void insertDetailWithSourceLine(
            JdbcTemplate jdbc,
            UUID detailId,
            UUID sourceLineId) {
        jdbc.update("""
                        insert into settlement_detail (
                            settlement_detail_id, created_at, fee_amount, fee_rate,
                            line_amount, line_settlement_amount, line_type,
                            occurred_at, order_product_id, settlement_id,
                            settlement_source_line_id
                        ) values (?, current_timestamp, 1.50, 0.1500, 10.00, 8.50,
                                  'SALE', ?, ?, ?, ?)
                        """,
                detailId,
                OCCURRED_AT.plusMinutes(detailId.getLeastSignificantBits()),
                UUID.randomUUID(),
                SETTLEMENT_ID,
                sourceLineId);
    }

    private UUID sourceLineId(JdbcTemplate jdbc, UUID detailId) {
        return jdbc.queryForObject(
                """
                        select settlement_source_line_id
                        from settlement_detail
                        where settlement_detail_id = ?
                        """,
                UUID.class,
                detailId);
    }

    private boolean constraintExists(JdbcTemplate jdbc, String constraintName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                            select 1
                            from information_schema.table_constraints
                            where table_schema = current_schema()
                              and constraint_name = ?
                        )
                        """,
                Boolean.class,
                constraintName));
    }

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                            select 1
                            from information_schema.tables
                            where table_schema = current_schema()
                              and table_name = ?
                        )
                        """,
                Boolean.class,
                tableName));
    }
}
