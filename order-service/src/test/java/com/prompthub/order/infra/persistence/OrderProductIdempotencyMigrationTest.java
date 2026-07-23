package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderProductIdempotencyMigrationTest extends PostgreSqlIntegrationTestSupport {

    private static final UUID BUYER_A = uuid(1);
    private static final UUID BUYER_B = uuid(2);
    private static final UUID PRODUCT_A = uuid(101);
    private static final UUID PRODUCT_B = uuid(102);
    private static final UUID SELLER_A = uuid(201);
    private static final UUID ORDER_A = uuid(301);
    private static final UUID ORDER_B = uuid(302);
    private static final UUID ORDER_PRODUCT_A = uuid(401);
    private static final UUID ORDER_PRODUCT_B = uuid(402);
    private static final UUID ORDER_PRODUCT_C = uuid(403);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private OrderExpirationStore orderExpirationStore;

    @Test
    void migrationRejectsSecondPendingProductForSameBuyerAndProduct() {
        insertOrder(ORDER_A, BUYER_A, "ORD-A");
        insertOrder(ORDER_B, BUYER_A, "ORD-B");
        insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");

        assertThatThrownBy(() ->
            insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_A, PRODUCT_A, "PENDING")
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                .contains("uk_order_product_buyer_product_pending"));
    }

    @Test
    void migrationAllowsDifferentBuyerOrProduct() {
        insertOrder(ORDER_A, BUYER_A, "ORD-A");
        insertOrder(ORDER_B, BUYER_B, "ORD-B");
        insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");
        insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_B, PRODUCT_A, "PENDING");
        insertProduct(ORDER_PRODUCT_C, ORDER_A, BUYER_A, PRODUCT_B, "PENDING");

        assertThat(countOrderProducts()).isEqualTo(3);
    }

    @Test
    void failedProductReleasesPendingUniqueness() {
        insertOrder(ORDER_A, BUYER_A, "ORD-A");
        insertOrder(ORDER_B, BUYER_A, "ORD-B");
        insertProduct(ORDER_PRODUCT_A, ORDER_A, BUYER_A, PRODUCT_A, "PENDING");
        jdbcTemplate.update(
            "update order_product set order_product_status = 'FAILED' where id = ?",
            ORDER_PRODUCT_A
        );

        insertProduct(ORDER_PRODUCT_B, ORDER_B, BUYER_A, PRODUCT_A, "PENDING");

        assertThat(countOrderProducts()).isEqualTo(2);
    }

    @Test
    void persistenceAdapterMapsRealPendingConstraintToO018() {
        Order first = order("ORD-FIRST", BUYER_A, PRODUCT_A);
        Order second = order("ORD-SECOND", BUYER_A, PRODUCT_A);
        orderRepository.saveAndFlush(first);

        assertThatThrownBy(() -> orderRepository.saveAndFlush(second))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
            );
    }

    @Test
    void multiProductConflictRollsBackEveryProductInNewOrder() {
        Order existing = order("ORD-EXISTING", BUYER_A, PRODUCT_A);
        orderRepository.saveAndFlush(existing);
        Order conflicting = Order.create(BUYER_A, "ORD-CONFLICTING", 20_000);
        conflicting.addOrderProduct(
            OrderProduct.create(PRODUCT_A, SELLER_A, "상품 A", 10_000)
        );
        conflicting.addOrderProduct(
            OrderProduct.create(PRODUCT_B, SELLER_A, "상품 B", 10_000)
        );

        assertThatThrownBy(() -> orderRepository.saveAndFlush(conflicting))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
            );
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from order_product where order_id = ?",
            Long.class,
            conflicting.getId()
        )).isZero();
    }

    @Test
    void migrationBackfillsBuyerIdFromParentOrder() {
        String schema = "backfill_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("create schema " + schema);
        try {
            DriverManagerDataSource dataSource = schemaDataSource(schema);
            JdbcTemplate schemaJdbc = new JdbcTemplate(dataSource);
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
            insertLegacyOrderAndProduct(schemaJdbc);

            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            assertThat(schemaJdbc.queryForObject(
                "select buyer_id from order_product where id = ?",
                UUID.class,
                ORDER_PRODUCT_A
            )).isEqualTo(BUYER_A);
        } finally {
            jdbcTemplate.execute("drop schema " + schema + " cascade");
        }
    }

    private void insertOrder(UUID orderId, UUID buyerId, String orderNumber) {
        jdbcTemplate.update("""
            insert into "order"
                (id, buyer_id, order_number, total_order_amount, order_status,
                 created_at, updated_at)
            values (?, ?, ?, 10000, 'CREATED', current_timestamp, current_timestamp)
            """, orderId, buyerId, orderNumber);
    }

    private Order order(String number, UUID buyerId, UUID productId) {
        Order order = Order.create(buyerId, number, 10_000);
        order.addOrderProduct(
            OrderProduct.create(productId, SELLER_A, "상품", 10_000)
        );
        return order;
    }

    private void insertProduct(
        UUID orderProductId,
        UUID orderId,
        UUID buyerId,
        UUID productId,
        String status
    ) {
        jdbcTemplate.update("""
            insert into order_product
                (id, order_id, buyer_id, product_id, seller_id,
                 product_title_snapshot, product_amount_snapshot,
                 order_product_status, downloaded, created_at, updated_at)
            values (?, ?, ?, ?, ?, '상품', 10000, ?, false,
                    current_timestamp, current_timestamp)
            """, orderProductId, orderId, buyerId, productId, SELLER_A, status);
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

    private void insertLegacyOrderAndProduct(JdbcTemplate schemaJdbc) {
        schemaJdbc.update("""
            insert into "order"
                (id, buyer_id, order_number, total_order_amount, order_status,
                 created_at, updated_at)
            values (?, ?, 'ORD-BACKFILL', 10000, 'CREATED',
                    current_timestamp, current_timestamp)
            """, ORDER_A, BUYER_A);
        schemaJdbc.update("""
            insert into order_product
                (id, order_id, product_id, seller_id,
                 product_title_snapshot, product_amount_snapshot,
                 order_product_status, downloaded, created_at, updated_at)
            values (?, ?, ?, ?, '상품', 10000, 'PENDING', false,
                    current_timestamp, current_timestamp)
            """, ORDER_PRODUCT_A, ORDER_A, PRODUCT_A, SELLER_A);
    }

    private long countOrderProducts() {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from order_product",
            Long.class
        );
        return count == null ? 0L : count;
    }

    private Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString(
            "00000000-0000-0000-0000-%012d".formatted(suffix)
        );
    }
}
