package com.prompthub.order.fixture;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
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

	public static CreateOrderRequest createOrderRequest() {
		return new CreateOrderRequest(List.of(PRODUCT_ID_1, PRODUCT_ID_2));
	}

	public static CreateOrderRequest createOrderRequestWithNullProductIds() {
		return new CreateOrderRequest(null);
	}

	public static CreateOrderRequest createOrderRequestWithEmptyProductIds() {
		return new CreateOrderRequest(List.of());
	}

	public static CreateOrderRequest createOrderRequestWithDuplicatedProductIds() {
		return new CreateOrderRequest(List.of(PRODUCT_ID_1, PRODUCT_ID_1));
	}

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
			TOTAL_AMOUNT,
			TOTAL_ITEM_COUNT
		);
		ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now());
		ReflectionTestUtils.setField(order, "updatedAt", LocalDateTime.now());
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
		order.updateOrderStatus(OrderStatus.CANCELED);
		return order;
	}

	public static OrderProduct createOrderProduct1() {
		return OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			null,
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
			null,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			"Claude-3",
			PRODUCT_AMOUNT_2
		);
	}

	public static List<UUID> productIds() {
		return List.of(PRODUCT_ID_1, PRODUCT_ID_2);
	}

	public static PaymentApprovedEvent createPaymentApprovedEvent(UUID orderId) {
		return createPaymentApprovedEvent(orderId, TOTAL_AMOUNT);
	}

	public static PaymentApprovedEvent createPaymentApprovedEvent(
		UUID orderId,
		int approvedAmount
	) {
		return new PaymentApprovedEvent(
			"PAYMENT_APPROVED",
			PAYMENT_ID,
			orderId,
			BUYER_ID,
			approvedAmount,
			APPROVED_AT.atOffset(ZoneOffset.UTC)
		);
	}



	public static PaymentRefundedEvent createPaymentRefundedEvent(UUID orderId) {
		return new PaymentRefundedEvent(
			"payment.refunded",
			PAYMENT_ID,
			orderId,
			BUYER_ID,
			TOTAL_AMOUNT,
			REFUNDED_AT.atOffset(ZoneOffset.UTC)
		);
	}

	public static OrderListProjection orderListProjection(
		OrderStatus orderStatus,
		OrderStatus orderProductStatus,
		boolean downloaded,
		Double rating
	) {
		return new OrderListProjection(
			ORDER_ID,
			ORDER_PRODUCT_ID,
			PRODUCT_ID_1,
			orderStatus,
			orderProductStatus,
			downloaded,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_TITLE_1,
			PRODUCT_MODEL,
			rating,
			PAID_AT,
			CREATED_AT
		);
	}

	public static OrderPaymentListProjection orderPaymentListProjection(
		OrderStatus orderStatus,
		OrderStatus orderProductStatus,
		LocalDateTime paidAt,
		boolean downloaded
	) {
		boolean isRefundable = orderStatus == OrderStatus.PAID && orderProductStatus == OrderStatus.PAID && !downloaded;
		return new OrderPaymentListProjection(
			ORDER_ID,
			PAYMENT_ID,
			orderStatus,
			isRefundable,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_TITLE_1,
			TOTAL_AMOUNT,
			paidAt,
			APPROVED_AT
		);
	}
}
