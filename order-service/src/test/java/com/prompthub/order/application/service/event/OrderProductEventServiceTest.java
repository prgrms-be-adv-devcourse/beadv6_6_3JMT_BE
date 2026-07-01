package com.prompthub.order.application.service.event;

import com.prompthub.order.application.event.product.ProductDeletedEvent;
import com.prompthub.order.application.event.product.ProductPriceChangedEvent;
import com.prompthub.order.application.event.product.ProductStoppedEvent;
import com.prompthub.order.domain.model.OrderProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static org.assertj.core.api.Assertions.assertThat;

class OrderProductEventServiceTest {

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 28, 15, 10);

	private final OrderProductEventService orderProductEventService = new OrderProductEventService();

	@Test
	@DisplayName("상품 이벤트를 처리해도 기존 OrderProduct 스냅샷은 변경하지 않는다")
	void productEvents_doNotMutateOrderProductSnapshot() {
		OrderProduct orderProduct = OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			null,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		);
		OrderProductSnapshot before = OrderProductSnapshot.from(orderProduct);

		orderProductEventService.handleProductStopped(new ProductStoppedEvent(
			"PRODUCT_STOPPED",
			PRODUCT_ID_1,
			OCCURRED_AT
		));
		orderProductEventService.handleProductDeleted(new ProductDeletedEvent(
			"PRODUCT_DELETED",
			PRODUCT_ID_1,
			OCCURRED_AT.plusMinutes(5)
		));
		orderProductEventService.handleProductPriceChanged(new ProductPriceChangedEvent(
			"PRODUCT_PRICE_CHANGED",
			PRODUCT_ID_1,
			PRODUCT_AMOUNT_1,
			PRODUCT_AMOUNT_1 + 5000,
			OCCURRED_AT.plusMinutes(10)
		));

		assertThat(OrderProductSnapshot.from(orderProduct)).isEqualTo(before);
	}

	private record OrderProductSnapshot(
		String productTitle,
		String productType,
		String productModel,
		int productAmount
	) {
		private static OrderProductSnapshot from(OrderProduct orderProduct) {
			return new OrderProductSnapshot(
				orderProduct.getProductTitle(),
				orderProduct.getProductType(),
				orderProduct.getProductModel(),
				orderProduct.getProductAmount()
			);
		}
	}
}
