package com.prompthub.order.fixture;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PaymentEventFixture {

	public static final UUID BUYER_ID = uuid(1);
	public static final UUID OTHER_BUYER_ID = uuid(2);
	public static final UUID PAYMENT_ID = uuid(401);
	public static final UUID ORDER_A = uuid(501);
	public static final UUID ORDER_B = uuid(502);
	public static final UUID ORDER_PRODUCT_A = uuid(601);
	public static final UUID ORDER_PRODUCT_B = uuid(602);
	public static final UUID PRODUCT_A = uuid(701);
	public static final UUID PRODUCT_B = uuid(702);
	public static final UUID SELLER_A = uuid(801);
	public static final UUID SELLER_B = uuid(802);
	public static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 7, 16, 10, 0, 5);
	public static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 16, 10, 0, 6);

	private PaymentEventFixture() {
	}

	public static List<Order> createdOrders() {
		return List.of(
			order(ORDER_A, ORDER_PRODUCT_A, PRODUCT_A, SELLER_A, BUYER_ID, "ORD-A", 10_000),
			order(ORDER_B, ORDER_PRODUCT_B, PRODUCT_B, SELLER_B, BUYER_ID, "ORD-B", 20_000)
		);
	}

	public static Order order(
		UUID orderId,
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		UUID buyerId,
		String orderNumber,
		int amount
	) {
		Order order = Order.create(buyerId, sellerId, orderNumber, amount);
		OrderProduct product = OrderProduct.create(productId, "상품-" + orderNumber, amount);
		ReflectionTestUtils.setField(order, "id", orderId);
		ReflectionTestUtils.setField(product, "id", orderProductId);
		order.addOrderProduct(product);
		return order;
	}

	public static PaymentFailedPayload failedPayload() {
		return new PaymentFailedPayload(
			PAYMENT_ID,
			List.of(ORDER_B, ORDER_A),
			"PAY_FAILED",
			"PG 결제 실패",
			FAILED_AT
		);
	}

	public static PaymentApprovedPayload approvedPayload(List<Order> orders) {
		List<PaymentApprovedOrderPayload> targets = orders.stream()
			.map(order -> new PaymentApprovedOrderPayload(
				order.getId(),
				order.getOrderProducts().stream()
					.map(OrderProduct::getId)
					.toList()
			))
			.toList();
		int totalAmount = orders.stream()
			.mapToInt(Order::getTotalOrderAmount)
			.sum();
		return new PaymentApprovedPayload(PAYMENT_ID, BUYER_ID, totalAmount, targets, APPROVED_AT);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
