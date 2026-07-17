package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
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
    void canAccessContent_completedOrderPaidProduct_returnsTrue() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);

        assertThat(order.canAccessContent(product)).isTrue();
    }

    @Test
    void canAccessContent_partialRefundedOrderRemainingPaidProduct_returnsTrue() {
        Order order = createPendingOrder();
        OrderProduct refundedProduct = createOrderProduct1();
        OrderProduct remainingProduct = createOrderProduct2();
        order.addOrderProduct(refundedProduct);
        order.addOrderProduct(remainingProduct);
        order.markCompleted(PAID_AT);
        order.refundOrderProduct(refundedProduct.getId(), refundedProduct.getProductAmount(), REFUNDED_AT);

        assertThat(order.canAccessContent(remainingProduct)).isTrue();
    }

    @Test
    void canAccessContent_partialRefundedOrderRefundedProduct_returnsFalse() {
        Order order = createPendingOrder();
        OrderProduct refundedProduct = createOrderProduct1();
        order.addOrderProduct(refundedProduct);
        order.addOrderProduct(createOrderProduct2());
        order.markCompleted(PAID_AT);
        order.refundOrderProduct(refundedProduct.getId(), refundedProduct.getProductAmount(), REFUNDED_AT);

        assertThat(order.canAccessContent(refundedProduct)).isFalse();
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
    void refundOrderProduct_refundsOnlyTargetAndRecalculatesPartialStatus() {
        Order order = createPendingOrder();
        OrderProduct first = createOrderProduct1();
        OrderProduct second = createOrderProduct2();
        order.addOrderProduct(first);
        order.addOrderProduct(second);
        order.markCompleted(PAID_AT);

        var refunded = order.refundOrderProduct(first.getId(), first.getProductAmount(), REFUNDED_AT);

        assertThat(refunded).containsSame(first);
        assertThat(first.getOrderStatus()).isEqualTo(OrderProductStatus.REFUNDED);
        assertThat(first.getRefundedAt()).isEqualTo(REFUNDED_AT);
        assertThat(second.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
        assertThat(second.getRefundedAt()).isNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
        assertThat(order.getRefundedAt()).isNull();
    }

    @Test
    void refundOrderProduct_lastPaidProductRecalculatesAllRefunded() {
        Order order = createPendingOrder();
        OrderProduct first = createOrderProduct1();
        OrderProduct second = createOrderProduct2();
        order.addOrderProduct(first);
        order.addOrderProduct(second);
        order.markCompleted(PAID_AT);
        order.refundOrderProduct(first.getId(), first.getProductAmount(), REFUNDED_AT);

        order.refundOrderProduct(second.getId(), second.getProductAmount(), REFUNDED_AT.plusMinutes(1));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT.plusMinutes(1));
    }

    @Test
    void refundOrderProduct_missingProduct_throwsNotFound() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);

        assertThatThrownBy(() -> order.refundOrderProduct(
            java.util.UUID.randomUUID(),
            product.getProductAmount(),
            REFUNDED_AT
        ))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_NOT_FOUND);
    }

    @Test
    void refundOrderProduct_amountMismatch_throwsDedicatedError() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);

        assertThatThrownBy(() -> order.refundOrderProduct(
            product.getId(),
            product.getProductAmount() - 1,
            REFUNDED_AT
        ))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_AMOUNT_MISMATCH);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
    }

    @Test
    void refundOrderProduct_nonPaidProduct_throwsInvalidTransition() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);

        assertThatThrownBy(() -> order.refundOrderProduct(
            product.getId(),
            product.getProductAmount(),
            REFUNDED_AT
        ))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PENDING);
    }

    @Test
    void refundOrderProduct_duplicateRefund_isIdempotent() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);
        order.refundOrderProduct(product.getId(), product.getProductAmount(), REFUNDED_AT);

        var duplicate = order.refundOrderProduct(
            product.getId(),
            product.getProductAmount(),
            REFUNDED_AT.plusMinutes(1)
        );

        assertThat(duplicate).isEmpty();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
        assertThat(product.getRefundedAt()).isEqualTo(REFUNDED_AT);
    }
}
