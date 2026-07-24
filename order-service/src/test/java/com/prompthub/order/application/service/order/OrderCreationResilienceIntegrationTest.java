package com.prompthub.order.application.service.order;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.service.cart.CartService;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
class OrderCreationResilienceIntegrationTest {

	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
	private static final String REQUEST_TITLE = "요청 제목";

	@Autowired
	private OrderCommandHandler orderCommandHandler;

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
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OrderProductIdempotencyStore orderProductIdempotencyStore;

	@BeforeEach
	void setUpReservationStore() {
		given(orderProductIdempotencyStore.acquire(
			any(UUID.class), anyCollection(), any(UUID.class), any(Duration.class)
		)).willReturn(true);
	}

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
	@DisplayName("조회용 Product 호출 실패 후 주문용 호출이 정상화되면 주문 상품을 Cart에서 제거한다")
	void queryFailureDoesNotPreventSubsequentOrderCreation() {
		saveCart();
		given(productClient.getCartSnapshots(anyList()))
			.willThrow(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> cartService.getCart(BUYER_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

		given(productClient.getOrderSnapshots(List.of(PRODUCT_ID))).willReturn(List.of(snapshot()));

		orderCommandHandler.createOrder(BUYER_ID, command());

		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(outboxEventPersistence.count()).isZero();
		assertCartEmpty();
	}

	private void assertOrderCreationFailureKeepsData(BusinessException productFailure) {
		saveCart();
		given(productClient.getOrderSnapshots(anyList())).willThrow(productFailure);

		assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, command()))
			.isSameAs(productFailure);

		assertThat(orderPersistence.count()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
		assertCartUnchanged();
	}

	private void saveCart() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID);
		cartPersistence.saveAndFlush(cart);
	}

	private void assertCartUnchanged() {
		assertThat(cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID))
			.hasValueSatisfying(savedCart -> assertThat(savedCart.getCartProducts())
				.extracting(product -> product.getProductId())
				.containsExactly(PRODUCT_ID));
	}

	private void assertCartEmpty() {
		assertThat(cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID))
			.hasValueSatisfying(savedCart -> assertThat(savedCart.getCartProducts()).isEmpty());
	}

	private CreateOrderCommand command() {
		return new CreateOrderCommand(List.of(new CreateOrderCommand.Product(PRODUCT_ID, REQUEST_TITLE)));
	}

	private ProductOrderSnapshot snapshot() {
		return new ProductOrderSnapshot(
			PRODUCT_ID,
			SELLER_ID,
			"서버 제목",
			"PROMPT",
			"GPT-4",
			10_000
		);
	}
}
