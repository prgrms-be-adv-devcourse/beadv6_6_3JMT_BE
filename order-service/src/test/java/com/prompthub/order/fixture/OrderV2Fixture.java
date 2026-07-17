package com.prompthub.order.fixture;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderV2Fixture {

	public static final UUID BUYER_ID = uuid(1);

	public static final UUID SELLER_A = uuid(101);
	public static final UUID SELLER_B = uuid(102);
	public static final UUID SELLER_C = uuid(103);

	public static final UUID PRODUCT_A1 = uuid(201);
	public static final UUID PRODUCT_A2 = uuid(202);
	public static final UUID PRODUCT_B1 = uuid(203);
	public static final UUID PRODUCT_C1 = uuid(204);
	public static final UUID UNKNOWN_PRODUCT = uuid(299);

	public static final UUID ORDER_A = uuid(501);
	public static final UUID ORDER_B = uuid(502);
	public static final UUID ORDER_C = uuid(503);

	public static final UUID ORDER_PRODUCT_A1 = uuid(601);
	public static final UUID ORDER_PRODUCT_A2 = uuid(602);
	public static final UUID ORDER_PRODUCT_B1 = uuid(603);
	public static final UUID ORDER_PRODUCT_C1 = uuid(604);

	public static final UUID ORDER_GROUP_ID = uuid(900);

	public static final String REQUEST_TITLE_A1 = "요청-A1";
	public static final String REQUEST_TITLE_A2 = "요청-A2";
	public static final String REQUEST_TITLE_B1 = "요청-B1";
	public static final String REQUEST_TITLE_C1 = "요청-C1";

	public static final int AMOUNT_A1 = 1_100;
	public static final int AMOUNT_A2 = 2_200;
	public static final int AMOUNT_B1 = 3_300;
	public static final int AMOUNT_C1 = 4_400;
	public static final int TOTAL_AMOUNT = 11_000;

	public static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 16, 12, 0);

	private OrderV2Fixture() {
	}

	public static CreateOrderCommand command() {
		return new CreateOrderCommand(List.of(
			new CreateOrderCommand.Product(PRODUCT_A1, REQUEST_TITLE_A1),
			new CreateOrderCommand.Product(PRODUCT_B1, REQUEST_TITLE_B1),
			new CreateOrderCommand.Product(PRODUCT_A2, REQUEST_TITLE_A2),
			new CreateOrderCommand.Product(PRODUCT_C1, REQUEST_TITLE_C1)
		));
	}

	public static List<UUID> requestedProductIds() {
		return List.of(PRODUCT_A1, PRODUCT_B1, PRODUCT_A2, PRODUCT_C1);
	}

	public static List<ProductOrderSnapshot> shuffledSnapshots() {
		return List.of(
			snapshot(PRODUCT_C1, SELLER_C, "서버-C1", AMOUNT_C1),
			snapshot(PRODUCT_A2, SELLER_A, "서버-A2", AMOUNT_A2),
			snapshot(PRODUCT_B1, SELLER_B, "서버-B1", AMOUNT_B1),
			snapshot(PRODUCT_A1, SELLER_A, "서버-A1", AMOUNT_A1)
		);
	}

	public static List<OrderItem> orderItems() {
		return List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1),
			new OrderItem(PRODUCT_B1, SELLER_B, REQUEST_TITLE_B1, AMOUNT_B1),
			new OrderItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2),
			new OrderItem(PRODUCT_C1, SELLER_C, REQUEST_TITLE_C1, AMOUNT_C1)
		);
	}

	public static CreateOrderResult result() {
		return new CreateOrderResult(TOTAL_AMOUNT, List.of(
			new CreateOrderResult.Order(
				ORDER_A,
				"ORD-A",
				BUYER_ID,
				OrderStatus.CREATED,
				AMOUNT_A1 + AMOUNT_A2,
				List.of(
					product(ORDER_PRODUCT_A1, PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1),
					product(ORDER_PRODUCT_A2, PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2)
				),
				CREATED_AT
			),
			new CreateOrderResult.Order(
				ORDER_B,
				"ORD-B",
				BUYER_ID,
				OrderStatus.CREATED,
				AMOUNT_B1,
				List.of(product(ORDER_PRODUCT_B1, PRODUCT_B1, SELLER_B, REQUEST_TITLE_B1, AMOUNT_B1)),
				CREATED_AT
			),
			new CreateOrderResult.Order(
				ORDER_C,
				"ORD-C",
				BUYER_ID,
				OrderStatus.CREATED,
				AMOUNT_C1,
				List.of(product(ORDER_PRODUCT_C1, PRODUCT_C1, SELLER_C, REQUEST_TITLE_C1, AMOUNT_C1)),
				CREATED_AT
			)
		));
	}

	public static String requestJson() {
		return """
			{
			  "products": [
			    {"productId": "%s", "productTitle": "%s"},
			    {"productId": "%s", "productTitle": "%s"},
			    {"productId": "%s", "productTitle": "%s"},
			    {"productId": "%s", "productTitle": "%s"}
			  ]
			}
			""".formatted(
			PRODUCT_A1, REQUEST_TITLE_A1,
			PRODUCT_B1, REQUEST_TITLE_B1,
			PRODUCT_A2, REQUEST_TITLE_A2,
			PRODUCT_C1, REQUEST_TITLE_C1
		);
	}

	public static ProductOrderSnapshot snapshot(
		UUID productId,
		UUID sellerId,
		String title,
		int amount
	) {
		return new ProductOrderSnapshot(productId, sellerId, title, "PROMPT", "GPT-4", amount);
	}

	private static CreateOrderResult.Product product(
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		String productTitle,
		int productAmount
	) {
		return new CreateOrderResult.Product(
			orderProductId,
			productId,
			sellerId,
			productTitle,
			productAmount,
			OrderProductStatus.PENDING
		);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
