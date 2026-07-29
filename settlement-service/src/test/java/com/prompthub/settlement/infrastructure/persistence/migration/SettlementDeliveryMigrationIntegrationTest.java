package com.prompthub.settlement.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SettlementDeliveryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine")
                    .withDatabaseName("settlement")
                    .withUsername("settlement")
                    .withPassword("settlement");

    @Test
    @DisplayName("V8은 Outbox를 Delivery 원장으로 교체하고 요청과 정산 중복을 막는다")
    void migrate_replacesOutboxAndEnforcesDeliveryIdentity() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbcTemplate();

        assertThat(tableExists(jdbc, "settlement_delivery")).isTrue();
        assertThat(tableExists(jdbc, "settlement_outbox_event")).isFalse();

        UUID settlementId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        insertSettlement(jdbc, settlementId, batchId);
        insertDelivery(jdbc, UUID.randomUUID(), requestId, settlementId, batchId);

        UUID otherSettlementId = UUID.randomUUID();
        insertSettlement(jdbc, otherSettlementId, batchId);
        assertThatThrownBy(() -> insertDelivery(
                jdbc, UUID.randomUUID(), requestId, otherSettlementId, batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDelivery(
                jdbc, UUID.randomUUID(), UUID.randomUUID(), settlementId, batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        return jdbc.queryForObject("""
                        select exists (
                            select 1
                            from information_schema.tables
                            where table_schema = current_schema()
                              and table_name = ?
                        )
                        """,
                Boolean.class,
                tableName);
    }

    private void insertSettlement(
            JdbcTemplate jdbc, UUID settlementId, UUID batchId) {
        jdbc.update("""
                        insert into settlement (
                            settlement_id,
                            calculated_at,
                            fee_total_amount,
                            period_end,
                            period_start,
                            product_count,
                            refund_amount,
                            seller_id,
                            settlement_batch_id,
                            settlement_total_amount,
                            total_amount
                        ) values (
                            ?,
                            current_timestamp,
                            15.00,
                            current_date,
                            current_date,
                            1,
                            0.00,
                            ?,
                            ?,
                            85.00,
                            100.00
                        )
                        """,
                settlementId,
                UUID.randomUUID(),
                batchId);
    }

    private void insertDelivery(
            JdbcTemplate jdbc,
            UUID deliveryId,
            UUID requestId,
            UUID settlementId,
            UUID batchId) {
        jdbc.update("""
                        insert into settlement_delivery (
                            settlement_delivery_id,
                            delivery_request_id,
                            settlement_id,
                            settlement_batch_id,
                            status,
                            attempt_count
                        ) values (?, ?, ?, ?, 'CALCULATED', 0)
                        """,
                deliveryId,
                requestId,
                settlementId,
                batchId);
    }
}
