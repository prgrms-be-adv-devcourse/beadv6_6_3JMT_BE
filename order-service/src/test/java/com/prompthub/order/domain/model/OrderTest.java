package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.CANCELED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_NUMBER;
import static com.prompthub.order.fixture.OrderFixture.PAID_AT;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createOrderProduct1;
import static com.prompthub.order.fixture.OrderFixture.createOrderProduct2;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrder;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
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
	void completeFreeOrder_changesOrderAndProductsAtomically() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 0);
		OrderProduct product = createOrderProduct1();
		order.addOrderProduct(product);

		order.completeFreeOrder();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getCompletedAt()).isNotNull();
		assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
	}

	@Test
	void completeFreeOrder_rejectsPositiveOrder() {
		Order order = createPendingOrder();

		assertThatThrownBy(order::completeFreeOrder)
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
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
    void markFailed_withFailureTime_onlyFailsPendingProducts() {
        Order order = createPendingOrder();
        OrderProduct pending = createOrderProduct1();
        OrderProduct paid = createOrderProduct2();
        OrderProduct alreadyFailed = OrderProduct.create(
            UUID.randomUUID(),
            paid.getSellerId(),
            "이미 실패한 상품",
            30_000
        );
        paid.markPaid();
        alreadyFailed.markFailed();
        LocalDateTime originalUpdatedAt = CANCELED_AT.minusHours(1);
        ReflectionTestUtils.setField(pending, "updatedAt", originalUpdatedAt);
        ReflectionTestUtils.setField(paid, "updatedAt", originalUpdatedAt);
        ReflectionTestUtils.setField(alreadyFailed, "updatedAt", originalUpdatedAt);
        order.addOrderProduct(pending);
        order.addOrderProduct(paid);
        order.addOrderProduct(alreadyFailed);

        order.markFailed(CANCELED_AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(pending.getOrderStatus()).isEqualTo(OrderProductStatus.FAILED);
        assertThat(pending.getCanceledAt()).isEqualTo(CANCELED_AT);
        assertThat(pending.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(paid.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
        assertThat(paid.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(paid.getCanceledAt()).isNull();
        assertThat(alreadyFailed.getOrderStatus()).isEqualTo(OrderProductStatus.FAILED);
        assertThat(alreadyFailed.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(alreadyFailed.getCanceledAt()).isNull();
    }

    @Test
    void markFailed_withFailureTime_rejectsCompletedOrderWithoutPartialMutation() {
        Order order = createPendingOrder();
        OrderProduct product = createOrderProduct1();
        order.addOrderProduct(product);
        order.markCompleted(PAID_AT);
        LocalDateTime productUpdatedAt = product.getUpdatedAt();

        assertThatThrownBy(() -> order.markFailed(CANCELED_AT))
            .isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS_TRANSITION);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isEqualTo(PAID_AT);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
        assertThat(product.getUpdatedAt()).isEqualTo(productUpdatedAt);
        assertThat(product.getCanceledAt()).isNull();
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
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_NOT_ALLOWED);
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

	@Test
	void requestRefund_marksOrderAndSelectedProductsOnly() {
		Order order = createPaidOrderWithProducts();
		OrderProduct first = order.getOrderProducts().getFirst();
		OrderProduct second = order.getOrderProducts().getLast();

		order.requestRefund(List.of(first.getId()));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(first.getOrderStatus()).isEqualTo(OrderProductStatus.REFUND_REQUESTED);
		assertThat(second.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
		assertThat(order.canAccessContent(first)).isFalse();
		assertThat(order.canAccessContent(second)).isTrue();
	}

	@Test
	void completeRefund_refundsAllSelectedProductsAndRecalculatesPartialStatus() {
		Order order = createPaidOrderWithProducts();
		List<OrderProduct> selected = List.copyOf(order.getOrderProducts());
		order.requestRefund(selected.stream().map(OrderProduct::getId).toList());

		List<OrderProduct> refunded = order.completeRequestedRefund(
			PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2,
			REFUNDED_AT
		);

		assertThat(refunded).containsExactlyElementsOf(selected);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
		assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
		assertThat(selected).allMatch(product -> product.getOrderStatus() == OrderProductStatus.REFUNDED);
	}
}
