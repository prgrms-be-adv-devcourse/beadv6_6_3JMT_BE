package com.prompthub.order.fixture;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderListProductProjection;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class OrderFixture {

	private OrderFixture() {
	}

	public static final UUID BUYER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000001");

	public static final UUID PRODUCT_ID_1 =
		UUID.fromString("00000000-0000-0000-0000-000000000101");

	public static final UUID PRODUCT_ID_2 =
		UUID.fromString("00000000-0000-0000-0000-000000000102");

	public static final UUID UNKNOWN_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000999");

	public static final UUID SELLER_ID_1 =
		UUID.fromString("00000000-0000-0000-0000-000000000201");

	public static final UUID SELLER_ID_2 =
		UUID.fromString("00000000-0000-0000-0000-000000000202");

	public static final UUID EVENT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000301");

	public static final UUID PAYMENT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000401");

	public static final UUID ORDER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000501");

	public static final UUID ORDER_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000601");

	public static final LocalDateTime APPROVED_AT =
		LocalDateTime.of(2026, 6, 19, 12, 0);



	public static final LocalDateTime CANCELED_AT =
		LocalDateTime.of(2026, 6, 19, 12, 10);

	public static final LocalDateTime REFUNDED_AT =
		LocalDateTime.of(2026, 6, 19, 12, 20);



	public static final LocalDateTime PAID_AT =
		LocalDateTime.of(2026, 6, 20, 12, 0);

	public static final LocalDateTime CREATED_AT =
		LocalDateTime.of(2026, 6, 20, 11, 58);

	public static final String ORDER_NUMBER = "ORD-20260619-0001";

	public static final String PRODUCT_TITLE_1 = "프롬프트 상품 1";
	public static final String PRODUCT_TITLE_2 = "프롬프트 상품 2";
	public static final String UNKNOWN_PRODUCT_TITLE = "요청하지 않은 상품";

	public static final String PRODUCT_TYPE_PROMPT = "PROMPT";
	public static final String PRODUCT_MODEL = "GPT-4.1";
	public static final String PRODUCT_THUMBNAIL_URL = "https://example.com/thumbnail.png";

	public static final int PRODUCT_AMOUNT_1 = 10_000;
	public static final int PRODUCT_AMOUNT_2 = 20_000;
	public static final int TOTAL_AMOUNT = PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2;
	public static final int TOTAL_ITEM_COUNT = 2;

	public static List<ProductOrderSnapshot> createProductSnapshots() {
		return List.of(
			createProductSnapshot1(),
			createProductSnapshot2()
		);
	}

	public static List<ProductOrderSnapshot> createSingleProductSnapshot() {
		return List.of(createProductSnapshot1());
	}

	public static List<ProductOrderSnapshot> createProductSnapshotsWithUnknownProduct() {
		return List.of(
			createProductSnapshot1(),
			new ProductOrderSnapshot(
				UNKNOWN_PRODUCT_ID,
				SELLER_ID_2,
				UNKNOWN_PRODUCT_TITLE,
				PRODUCT_TYPE_PROMPT,
				"GPT-4",
				PRODUCT_AMOUNT_2
			)
		);
	}

	public static ProductOrderSnapshot createProductSnapshot1() {
		return new ProductOrderSnapshot(
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		);
	}

	public static ProductOrderSnapshot createProductSnapshot2() {
		return new ProductOrderSnapshot(
			PRODUCT_ID_2,
			SELLER_ID_2,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_2
		);
	}

	public static Order createPendingOrder() {
		Order order = Order.create(
			BUYER_ID,
			ORDER_NUMBER,
			TOTAL_AMOUNT
		);
		ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
		return order;
	}

	public static Order createPendingOrderWithProducts() {
		Order order = createPendingOrder();

		order.addOrderProduct(createOrderProduct1());
		order.addOrderProduct(createOrderProduct2());

		return order;
	}

	public static Order createPaidOrderWithProducts() {
		Order order = createPendingOrderWithProducts();
		order.markPaid();
		return order;
	}

	public static Order createCanceledOrderWithProducts() {
		Order order = createPendingOrderWithProducts();
		order.markFailed(CANCELED_AT);
		return order;
	}

	public static Order createFailedOrderWithProducts() {
		Order order = createPendingOrderWithProducts();
		order.markFailed(CANCELED_AT); // Assuming failure also uses CANCELED_AT in tests
		return order;
	}

	public static Order createRefundedOrderWithProducts() {
		Order order = createPaidOrderWithProducts();
		order.getOrderProducts().forEach(op ->
			order.refundOrderProduct(op.getId(), op.getProductAmount(), REFUNDED_AT)
		);
		return order;
	}

	public static OrderProduct createOrderProduct1() {
		return OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_MODEL,
			PRODUCT_AMOUNT_1
		);
	}

	public static OrderProduct createOrderProduct2() {
		return OrderProduct.create(
			PRODUCT_ID_2,
			SELLER_ID_2,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			"Claude-3",
			PRODUCT_AMOUNT_2
		);
	}

	public static List<UUID> productIds() {
		return List.of(PRODUCT_ID_1, PRODUCT_ID_2);
	}

	public static OrderListProjection orderListProjection(
		OrderStatus orderStatus
	) {
		return new OrderListProjection(
			ORDER_ID,
			ORDER_NUMBER,
			orderStatus,
			TOTAL_AMOUNT,
			PAID_AT,
			CREATED_AT
		);
	}

	public static OrderListProductProjection orderListProductProjection(
		OrderProductStatus orderProductStatus,
		boolean downloaded,
		Double rating
	) {
		return new OrderListProductProjection(
			ORDER_ID,
			ORDER_PRODUCT_ID,
			PRODUCT_ID_1,
			orderProductStatus,
			PRODUCT_AMOUNT_1,
			downloaded,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_TITLE_1,
			PRODUCT_MODEL,
			rating
		);
	}
}
