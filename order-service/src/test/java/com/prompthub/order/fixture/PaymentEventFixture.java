package com.prompthub.order.fixture;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
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
	public static final UUID ORDER_PRODUCT_C = uuid(603);
	public static final UUID ORDER_PRODUCT_D = uuid(604);
	public static final UUID PRODUCT_A = uuid(701);
	public static final UUID PRODUCT_B = uuid(702);
	public static final UUID PRODUCT_C = uuid(703);
	public static final UUID PRODUCT_D = uuid(704);
	public static final UUID SELLER_A = uuid(801);
	public static final UUID SELLER_B = uuid(802);
	public static final UUID SELLER_C = uuid(803);
	public static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 7, 17, 10, 0, 5);
	public static final String APPROVED_AT_OFFSET = "2026-07-17T10:00:05+09:00";
	public static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 17, 10, 0, 6);

	private PaymentEventFixture() {
	}

	public static Order createdOrder() {
		Order order = Order.create(BUYER_ID, "ORD-A", 100_000);
		ReflectionTestUtils.setField(order, "id", ORDER_A);
		addProduct(order, ORDER_PRODUCT_D, PRODUCT_D, SELLER_A, 40_000);
		addProduct(order, ORDER_PRODUCT_B, PRODUCT_B, SELLER_B, 20_000);
		addProduct(order, ORDER_PRODUCT_A, PRODUCT_A, SELLER_A, 10_000);
		addProduct(order, ORDER_PRODUCT_C, PRODUCT_C, SELLER_C, 30_000);
		return order;
	}

	public static List<UUID> productIds() {
		return List.of(PRODUCT_A, PRODUCT_B, PRODUCT_C, PRODUCT_D);
	}

	public static PaymentFailedPayload failedPayload() {
		return new PaymentFailedPayload(PAYMENT_ID, ORDER_A, BUYER_ID);
	}

	public static PaymentApprovedPayload approvedPayload(Order order) {
		return new PaymentApprovedPayload(
			PAYMENT_ID,
			order.getId(),
			order.getBuyerId(),
			order.getTotalOrderAmount(),
			APPROVED_AT_OFFSET
		);
	}

	private static void addProduct(
		Order order,
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		int amount
	) {
		OrderProduct product = OrderProduct.create(productId, sellerId, "상품-" + amount, amount);
		ReflectionTestUtils.setField(product, "id", orderProductId);
		order.addOrderProduct(product);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
