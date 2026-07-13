package com.prompthub.order.fixture;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;

public final class OrderRefundFixture {

	private OrderRefundFixture() {
	}

	public static OrderRefund createRequestedRefund(
		Order order,
		UUID refundId,
		LocalDateTime requestedAt
	) {
		return OrderRefund.create(refundId, order, PAYMENT_ID, BUYER_ID, null, requestedAt);
	}

	public static OrderRefund createRequestedRefundWithAllProducts(
		Order order,
		UUID refundId,
		LocalDateTime requestedAt
	) {
		OrderRefund refund = createRequestedRefund(order, refundId, requestedAt);
		order.getOrderProducts().forEach(product -> {
			product.requestRefund();
			refund.addProduct(product);
		});
		return refund;
	}
}
