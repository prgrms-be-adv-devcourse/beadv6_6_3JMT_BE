package com.prompthub.order.application.service.order;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.service.cart.CartService;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
class OrderCreationResilienceIntegrationTest {

	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");

	@Autowired
	private OrderService orderService;

	@Autowired
	private CartService cartService;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@AfterEach
	void tearDown() {
		outboxEventPersistence.deleteAll();
		orderPersistence.deleteAll();
		cartPersistence.deleteAll();
	}

	@Test
	@DisplayName("Product 호출이 SYS002로 실패하면 주문, 장바구니, Outbox가 변경되지 않는다")
	void productServiceUnavailableKeepsOrderAndCartUnchanged() {
		assertOrderCreationFailureKeepsData(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
	}

	@Test
	@DisplayName("Circuit Open이 SYS002로 변환되면 주문, 장바구니, Outbox가 변경되지 않는다")
	void circuitOpenConvertedToSys002KeepsOrderAndCartUnchanged() {
		assertOrderCreationFailureKeepsData(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
	}

	@Test
	@DisplayName("Bulkhead Full이 SYS002로 변환되면 주문, 장바구니, Outbox가 변경되지 않는다")
	void bulkheadFullConvertedToSys002KeepsOrderAndCartUnchanged() {
		assertOrderCreationFailureKeepsData(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
	}

	@Test
	@DisplayName("조회용 Product 호출이 실패해도 주문용 Product 호출이 정상화되면 주문 생성은 진행된다")
	void queryFailureDoesNotPreventSubsequentOrderCreation() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID);
		cartPersistence.saveAndFlush(cart);
		given(productClient.getCartSnapshots(anyList()))
			.willThrow(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> cartService.getCart(BUYER_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

		given(productClient.getOrderSnapshots(List.of(PRODUCT_ID))).willReturn(List.of(new ProductOrderSnapshot(
			PRODUCT_ID,
			UUID.fromString("00000000-0000-0000-0000-000000000903"),
			"테스트 상품",
			"PROMPT",
			"GPT-4",
			10_000
		)));

		orderService.createOrder(BUYER_ID, new CreateOrderRequest(List.of(PRODUCT_ID)));

		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID))
			.hasValueSatisfying(savedCart -> assertThat(savedCart.getCartProducts()).isEmpty());
	}

	private void assertOrderCreationFailureKeepsData(BusinessException productFailure) {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID);
		cartPersistence.saveAndFlush(cart);
		given(productClient.getOrderSnapshots(anyList())).willThrow(productFailure);

		assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, new CreateOrderRequest(List.of(PRODUCT_ID))))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

		assertThat(orderPersistence.count()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID))
			.hasValueSatisfying(savedCart -> assertThat(savedCart.getCartProducts())
				.extracting(cartProduct -> cartProduct.getProductId())
				.containsExactly(PRODUCT_ID));
	}
}
