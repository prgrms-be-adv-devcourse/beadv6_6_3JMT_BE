package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceTest {

	private static final LocalDateTime EXPIRED_AT = CREATED_AT.plusMinutes(20);

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private OrderExpirationPolicy expirationPolicy;

	@InjectMocks
	private OrderExpirationService orderExpirationService;

	@Nested
	@DisplayName("결제 대기 주문 만료 취소")
	class CancelPendingOrderByTimeout {

		@Test
		@DisplayName("20분이 지난 PENDING 주문은 CANCELED 처리하고 장바구니 상품을 복구한다")
		void cancelPendingOrderByTimeout_expiredPendingOrder_cancelsAndRestoresCart() {
			// given
			Order order = createPendingOrderWithProducts();
			Cart cart = Cart.create(BUYER_ID);
			cart.addProduct(PRODUCT_ID_1);

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));
			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.of(cart));
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(order.getCanceledAt()).isEqualTo(EXPIRED_AT);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderProductStatus)
				.containsOnly(OrderProductStatus.CANCELED);
			assertThat(cart.getCartProducts())
				.extracting(cartProduct -> cartProduct.getProductId())
				.containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);
			then(cartRepository).should().save(cart);
		}

		@Test
		@DisplayName("장바구니가 없으면 새 장바구니를 만들어 주문상품을 복구한다")
		void cancelPendingOrderByTimeout_withoutCart_createsCartAndRestoresProducts() {
			// given
			Order order = createPendingOrderWithProducts();

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));
			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.empty());
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			then(cartRepository).should().save(cartCaptor.capture());
			assertThat(cartCaptor.getValue().getBuyerId()).isEqualTo(BUYER_ID);
			assertThat(cartCaptor.getValue().getCartProducts())
				.extracting(cartProduct -> cartProduct.getProductId())
				.containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);
		}

		@Test
		@DisplayName("20분이 지나지 않은 PENDING 주문은 상태 변경과 장바구니 복구를 하지 않는다")
		void cancelPendingOrderByTimeout_notExpiredPendingOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT.minusMinutes(1));

			// then
			assertThat(completed).isFalse();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 PAID 상태인 주문은 상태 변경과 장바구니 복구를 하지 않는다")
		void cancelPendingOrderByTimeout_paidOrder_doNothing() {
			// given
			Order order = createPaidOrderWithProducts();

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("FAILED 상태인 주문은 상태 변경과 장바구니 복구를 하지 않는다")
		void cancelPendingOrderByTimeout_failedOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.FAILED);

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("CANCELED 상태인 주문은 상태 변경과 장바구니 복구를 하지 않는다")
		void cancelPendingOrderByTimeout_canceledOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.CANCELED);

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("REFUNDED 상태인 주문은 상태 변경과 장바구니 복구를 하지 않는다")
		void cancelPendingOrderByTimeout_refundedOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.REFUNDED);

			given(orderRepository.findByIdWithOrderProducts(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("Redis에 남은 주문 ID가 DB에 없으면 아무 작업도 하지 않는다")
		void cancelPendingOrderByTimeout_orderNotFound_doNothing() {
			// given
			given(orderRepository.findByIdWithOrderProducts(ORDER_ID))
				.willReturn(Optional.empty());

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(ORDER_ID, EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			then(cartRepository).shouldHaveNoInteractions();
			then(cartRepository).should(never()).save(any());
		}
	}
}
