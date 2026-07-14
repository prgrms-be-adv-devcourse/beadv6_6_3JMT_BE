package com.prompthub.order.infra.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OrderRefundMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() throws SQLException {
        createVersionOneSchema();

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()
            .migrate();
    }

    @Test
    @DisplayName("부분 환불 상태와 환불 재처리 이력을 저장할 수 있다")
    void migration_supportsPartialRefundAndRetryHistory() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO public."order" (id, order_status)
                VALUES ('00000000-0000-0000-0000-000000000001', 'PARTIALLY_REFUNDED')
                """);
            statement.executeUpdate("""
                INSERT INTO public.order_product (id, order_product_status)
                VALUES ('00000000-0000-0000-0000-000000000011', 'REFUND_REQUESTED')
                """);
            statement.executeUpdate("""
                INSERT INTO public.order_refund (
                    id, order_id, payment_id, status, total_refund_amount,
                    reconciliation_attempt, manual_review_required
                ) VALUES (
                    '00000000-0000-0000-0000-000000000101',
                    '00000000-0000-0000-0000-000000000001',
                    '00000000-0000-0000-0000-000000000201',
                    'PROCESSING', 1000, 0, false
                )
                """);
            statement.executeUpdate("""
                INSERT INTO public.order_refund (
                    id, order_id, payment_id, status, total_refund_amount,
                    reconciliation_attempt, manual_review_required
                ) VALUES (
                    '00000000-0000-0000-0000-000000000102',
                    '00000000-0000-0000-0000-000000000001',
                    '00000000-0000-0000-0000-000000000201',
                    'UNKNOWN', 1000, 1, false
                )
                """);
            statement.executeUpdate("""
                INSERT INTO public.order_refund_product (
                    id, order_refund_id, order_product_id, refund_amount
                ) VALUES
                    ('00000000-0000-0000-0000-000000000301',
                     '00000000-0000-0000-0000-000000000101',
                     '00000000-0000-0000-0000-000000000011', 1000),
                    ('00000000-0000-0000-0000-000000000302',
                     '00000000-0000-0000-0000-000000000102',
                     '00000000-0000-0000-0000-000000000011', 1000)
                """);

            try (ResultSet result = statement.executeQuery("""
                SELECT version, retryable
                FROM public.order_refund
                WHERE id = '00000000-0000-0000-0000-000000000101'
                """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("version")).isZero();
                assertThat(result.getBoolean("retryable")).isFalse();
            }
        }
    }

    private static void createVersionOneSchema() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE public."order" (
                    id uuid PRIMARY KEY,
                    order_status varchar(20) NOT NULL,
                    CONSTRAINT order_order_status_check
                        CHECK (order_status IN ('PENDING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED'))
                )
                """);
            statement.execute("""
                CREATE TABLE public.order_product (
                    id uuid PRIMARY KEY,
                    order_product_status varchar(20) NOT NULL,
                    CONSTRAINT order_product_order_product_status_check
                        CHECK (order_product_status IN ('PENDING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED'))
                )
                """);
            statement.execute("""
                CREATE TABLE public.order_refund (
                    id uuid PRIMARY KEY,
                    order_id uuid NOT NULL,
                    payment_id uuid NOT NULL,
                    status varchar(20) NOT NULL,
                    total_refund_amount integer NOT NULL,
                    reconciliation_attempt integer NOT NULL,
                    manual_review_required boolean NOT NULL,
                    next_check_at timestamp,
                    failure_reason varchar(1000),
                    CONSTRAINT order_refund_status_check
                        CHECK (status IN ('REQUESTED', 'COMPLETED', 'FAILED', 'TIMEOUT'))
                )
                """);
            statement.execute("""
                CREATE TABLE public.order_refund_product (
                    id uuid PRIMARY KEY,
                    order_refund_id uuid NOT NULL,
                    order_product_id uuid NOT NULL,
                    refund_amount integer NOT NULL,
                    CONSTRAINT uk_order_refund_product_order_product UNIQUE (order_product_id),
                    CONSTRAINT uk_order_refund_product_refund_product
                        UNIQUE (order_refund_id, order_product_id)
                )
                """);
        }
    }
}
