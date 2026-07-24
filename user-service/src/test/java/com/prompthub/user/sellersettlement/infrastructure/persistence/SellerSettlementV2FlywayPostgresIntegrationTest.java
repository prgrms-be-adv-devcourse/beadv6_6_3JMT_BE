package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerSettlementV2FlywayPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("user")
            .withUsername("user")
            .withPassword("user");

    @BeforeEach
    void cleanDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    @DisplayName("V3는 기존 시딩을 초기화하고 PostgreSQL V2 parent·detail 제약을 적용한다")
    void migrate_resetsLegacySeedAndEnforcesV2Constraints() {
        migrateThroughV2();
        JdbcTemplate jdbc = jdbcTemplate();
        insertSettlement(jdbc, UUID.randomUUID(), UUID.randomUUID(), (short) 1);

        migrateAll();

        assertThat(jdbc.queryForObject("select count(*) from seller_settlement", Integer.class)).isZero();
        UUID sellerSettlementId = UUID.randomUUID();
        insertSettlement(jdbc, sellerSettlementId, UUID.randomUUID(), (short) 2);
        insertDetail(jdbc, UUID.randomUUID(), sellerSettlementId, "SALE");

        assertThat(jdbc.queryForObject("select count(*) from seller_settlement_detail", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> insertSettlement(jdbc, UUID.randomUUID(), UUID.randomUUID(), (short) 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDetail(jdbc, UUID.randomUUID(), sellerSettlementId, "ADJUSTMENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDetail(jdbc, UUID.randomUUID(), UUID.randomUUID(), "SALE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrateThroughV2() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
    }

    private void migrateAll() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private void insertSettlement(
            JdbcTemplate jdbc,
            UUID sellerSettlementId,
            UUID settlementId,
            short payloadVersion
    ) {
        String columns = payloadVersion == 1
                ? """
                seller_settlement_id, settlement_id, seller_id, period_start, period_end, product_count,
                total_amount, settlement_total_amount, fee_total_amount, refund_amount, calculated_at,
                status, created_at, updated_at
                """
                : """
                seller_settlement_id, settlement_id, seller_id, period_start, period_end, product_count,
                total_amount, settlement_total_amount, fee_total_amount, refund_amount, calculated_at,
                payload_version, status, created_at, updated_at
                """;
        String values = payloadVersion == 1
                ? "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp"
                : "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp";
        Object[] parameters = payloadVersion == 1
                ? parentParameters(sellerSettlementId, settlementId)
                : parentParameters(sellerSettlementId, settlementId, payloadVersion);
        jdbc.update("insert into seller_settlement (" + columns + ") values (" + values + ")", parameters);
    }

    private Object[] parentParameters(UUID sellerSettlementId, UUID settlementId) {
        return new Object[]{
                sellerSettlementId, settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19), 1,
                new BigDecimal("100.00"), new BigDecimal("85.00"), new BigDecimal("15.00"),
                BigDecimal.ZERO, LocalDateTime.of(2026, 7, 20, 1, 0), "WAITING"
        };
    }

    private Object[] parentParameters(UUID sellerSettlementId, UUID settlementId, short payloadVersion) {
        Object[] legacy = parentParameters(sellerSettlementId, settlementId);
        Object[] parameters = new Object[legacy.length + 1];
        System.arraycopy(legacy, 0, parameters, 0, 11);
        parameters[11] = payloadVersion;
        parameters[12] = legacy[11];
        return parameters;
    }

    private void insertDetail(
            JdbcTemplate jdbc,
            UUID detailId,
            UUID sellerSettlementId,
            String lineType
    ) {
        jdbc.update("""
                        insert into seller_settlement_detail (
                            settlement_detail_id, seller_settlement_id, order_product_id, line_type,
                            line_amount, fee_rate, fee_amount, line_settlement_amount, occurred_at, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                        """,
                detailId,
                sellerSettlementId,
                UUID.randomUUID(),
                lineType,
                new BigDecimal("100.00"),
                new BigDecimal("0.1500"),
                new BigDecimal("15.00"),
                new BigDecimal("85.00"),
                LocalDateTime.of(2026, 7, 14, 13, 10));
    }
}
