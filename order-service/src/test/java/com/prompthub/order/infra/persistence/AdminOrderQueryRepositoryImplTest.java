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
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
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
    void searchAdminOrders_groupsProductsBySellerWithoutLosingOrderTotals() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 24, 10, 0);
        UUID sellerId3 = UUID.fromString("00000000-0000-0000-0000-000000000203");
        Order order = Order.create(
            BUYER_ID,
            "ORD-20260624-0001",
            70_000
        );
        OrderProduct sellerAFirst = OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, 10_000);
        OrderProduct sellerASecond = OrderProduct.create(PRODUCT_ID_2, SELLER_ID_1, PRODUCT_TITLE_2, 20_000);
        OrderProduct sellerB = OrderProduct.create(UUID.fromString("00000000-0000-0000-0000-000000000103"), SELLER_ID_2, "프롬프트 상품 3", 15_000);
        OrderProduct sellerC = OrderProduct.create(UUID.fromString("00000000-0000-0000-0000-000000000104"), sellerId3, "프롬프트 상품 4", 25_000);
        order.addOrderProduct(sellerAFirst);
        order.addOrderProduct(sellerASecond);
        order.addOrderProduct(sellerB);
        order.addOrderProduct(sellerC);
        setAuditTimes(order, createdAt);
        setProductAuditTimes(sellerAFirst, createdAt.plusSeconds(1));
        setProductAuditTimes(sellerASecond, createdAt.plusSeconds(2));
        setProductAuditTimes(sellerB, createdAt.plusSeconds(3));
        setProductAuditTimes(sellerC, createdAt.plusSeconds(4));
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
        assertThat(projection.productTitle()).isEqualTo(PRODUCT_TITLE_1 + " 외 3건");
        assertThat(projection.totalOrderCount()).isEqualTo(4);
        assertThat(projection.totalOrderAmount()).isEqualTo(70_000);
        assertThat(projection.orderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(projection.sellers()).containsExactly(
            new AdminOrderListProjection.SellerSummary(SELLER_ID_1, 2, 30_000),
            new AdminOrderListProjection.SellerSummary(SELLER_ID_2, 1, 15_000),
            new AdminOrderListProjection.SellerSummary(sellerId3, 1, 25_000)
        );
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

    private void setProductAuditTimes(OrderProduct orderProduct, LocalDateTime createdAt) {
        ReflectionTestUtils.setField(orderProduct, "createdAt", createdAt);
        ReflectionTestUtils.setField(orderProduct, "updatedAt", createdAt);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
