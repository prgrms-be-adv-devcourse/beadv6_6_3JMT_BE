package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_NUMBER;
import static com.prompthub.order.fixture.OrderFixture.PAID_AT;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createOrderProduct1;
import static com.prompthub.order.fixture.OrderFixture.createOrderProduct2;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

	@Test
	void create_startsCreated() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, TOTAL_AMOUNT);

		assertThat(order.getBuyerId()).isEqualTo(BUYER_ID);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCompletedAt()).isNull();
        assertThat(order.getRefundedAt()).isNull();
    }

    @Test
    void markCompleted_changesOrderAndProductsAtomically() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);

        order.markCompleted(PAID_AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isEqualTo(PAID_AT);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
    }

    @Test
    void failedOrder_canBecomeCompletedAfterPaymentRetry() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markFailed();

        order.markCompleted(PAID_AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
    }

    @Test
    void completedOrder_cannotBecomeFailed() {
        Order order = createPendingOrder();
        order.markCompleted(PAID_AT);

        assertThatThrownBy(order::markFailed).isInstanceOf(OrderException.class);
    }

    @Test
    void refundedChildren_recalculatePartialAndAllRefunded() {
        Order order = createPendingOrder();
        OrderProduct first = createOrderProduct1();
        OrderProduct second = createOrderProduct2();
        order.addOrderProduct(first);
        order.addOrderProduct(second);
        order.markCompleted(PAID_AT);

        first.refund(REFUNDED_AT);
        order.recalculateRefundStatus(REFUNDED_AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
        assertThat(order.getRefundedAt()).isNull();

        second.refund(REFUNDED_AT.plusMinutes(1));
        order.recalculateRefundStatus(REFUNDED_AT.plusMinutes(1));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT.plusMinutes(1));
    }

    @Test
    void duplicateRefundRecalculation_isIdempotent() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);
        product.refund(REFUNDED_AT);
        order.recalculateRefundStatus(REFUNDED_AT);

        order.recalculateRefundStatus(REFUNDED_AT.plusMinutes(1));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
    }
}
