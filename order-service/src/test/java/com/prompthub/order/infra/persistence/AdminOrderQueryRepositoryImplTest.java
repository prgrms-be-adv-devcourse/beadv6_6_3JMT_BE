package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.AdminOrderQueryRepositoryImpl;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class, AdminOrderQueryRepositoryImpl.class})
class AdminOrderQueryRepositoryImplTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AdminOrderQueryRepositoryImpl repository;

    @Test
    void searchAdminOrders_usesOrderSellerAndCountsProducts() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 24, 10, 0);
        Order order = Order.create(
            BUYER_ID,
            "ORD-20260624-0001",
            PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2
        );
        order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_AMOUNT_1));
        order.addOrderProduct(OrderProduct.create(PRODUCT_ID_2, SELLER_ID_1, PRODUCT_TITLE_2, PRODUCT_AMOUNT_2));
        setAuditTimes(order, createdAt);
        order.markCompleted(createdAt.plusMinutes(1));
        entityManager.persist(order);
        flushAndClear();

        Page<AdminOrderListProjection> result = repository.searchAdminOrders(
            new AdminOrderSearchCondition("ALL", 1, 20).resolve(),
            PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        AdminOrderListProjection projection = result.getContent().getFirst();
        assertThat(projection.orderId()).isEqualTo(order.getId());
        assertThat(projection.sellerId()).isEqualTo(SELLER_ID_1);
        assertThat(projection.productTitle()).isEqualTo(PRODUCT_TITLE_1 + " 외 1건");
        assertThat(projection.totalOrderCount()).isEqualTo(2);
        assertThat(projection.totalOrderAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
        assertThat(projection.orderStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void searchAdminOrders_filtersByCompletedStatus() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 24, 10, 0);
        Order completed = completedOrder(
            "ORD-20260624-0002",
            PRODUCT_ID_1,
            PRODUCT_TITLE_1,
            PRODUCT_AMOUNT_1,
            createdAt
        );
        Order created = Order.create(
            BUYER_ID,
            "ORD-20260624-0003",
            PRODUCT_AMOUNT_2
        );
        created.addOrderProduct(OrderProduct.create(PRODUCT_ID_2, SELLER_ID_1, PRODUCT_TITLE_2, PRODUCT_AMOUNT_2));
        setAuditTimes(created, createdAt.plusHours(1));
        entityManager.persist(completed);
        entityManager.persist(created);
        flushAndClear();

        Page<AdminOrderListProjection> result = repository.searchAdminOrders(
            new AdminOrderSearchCondition("COMPLETED", 1, 20).resolve(),
            PageRequest.of(0, 20)
        );

        assertThat(result.getContent())
            .extracting(AdminOrderListProjection::orderId)
            .containsExactly(completed.getId());
    }

    @Test
    void sumMonthlyTransactionAmount_subtractsRefundedProductAmount() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 1, 0, 0);
        Order refunded = completedOrder(
            "ORD-20260610-0001",
            PRODUCT_ID_1,
            PRODUCT_TITLE_1,
            PRODUCT_AMOUNT_1,
            LocalDateTime.of(2026, 6, 10, 10, 0)
        );
        Order completed = completedOrder(
            "ORD-20260611-0001",
            PRODUCT_ID_2,
            PRODUCT_TITLE_2,
            PRODUCT_AMOUNT_2,
            LocalDateTime.of(2026, 6, 11, 10, 0)
        );
        LocalDateTime refundedAt = LocalDateTime.of(2026, 6, 12, 10, 0);
        refunded.getOrderProducts().getFirst().refund(refundedAt);
        refunded.recalculateRefundStatus(refundedAt);
        entityManager.persist(refunded);
        entityManager.persist(completed);
        flushAndClear();

        long result = repository.sumMonthlyTransactionAmount(start, end);

        assertThat(result).isEqualTo(PRODUCT_AMOUNT_2);
    }

    @Test
    void findDailyTransactions_usesCompletionAndRefundDates() {
        LocalDate completedDay = LocalDate.of(2026, 6, 24);
        LocalDate refundedDay = completedDay.plusDays(1);
        Order refunded = completedOrder(
            "ORD-20260624-0004",
            PRODUCT_ID_1,
            PRODUCT_TITLE_1,
            PRODUCT_AMOUNT_1,
            completedDay.atTime(10, 0)
        );
        Order completed = completedOrder(
            "ORD-20260624-0005",
            PRODUCT_ID_2,
            PRODUCT_TITLE_2,
            PRODUCT_AMOUNT_2,
            completedDay.atTime(11, 0)
        );
        refunded.getOrderProducts().getFirst().refund(refundedDay.atTime(9, 0));
        refunded.recalculateRefundStatus(refundedDay.atTime(9, 0));
        entityManager.persist(refunded);
        entityManager.persist(completed);
        flushAndClear();

        List<AdminDailyTransactionProjection> result = repository.findDailyTransactions(
            completedDay.atStartOfDay(),
            refundedDay.plusDays(1).atStartOfDay()
        );

        assertThat(result).hasSize(2);
        assertThat(result.get(0).date()).isEqualTo(completedDay);
        assertThat(result.get(0).transactionCount()).isEqualTo(2L);
        assertThat(result.get(0).transactionAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
        assertThat(result.get(1).date()).isEqualTo(refundedDay);
        assertThat(result.get(1).transactionCount()).isZero();
        assertThat(result.get(1).transactionAmount()).isEqualTo(-PRODUCT_AMOUNT_1);
    }

    private Order completedOrder(
        String orderNumber,
        UUID productId,
        String productTitle,
        int amount,
        LocalDateTime createdAt
    ) {
        Order order = Order.create(BUYER_ID, orderNumber, amount);
        order.addOrderProduct(OrderProduct.create(productId, SELLER_ID_1, productTitle, amount));
        setAuditTimes(order, createdAt);
        order.markCompleted(createdAt.plusMinutes(1));
        return order;
    }

    private void setAuditTimes(Order order, LocalDateTime createdAt) {
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        ReflectionTestUtils.setField(order, "updatedAt", createdAt);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
