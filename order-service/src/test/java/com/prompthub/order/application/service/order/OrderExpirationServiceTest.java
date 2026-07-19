package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceTest {

	private static final LocalDateTime EXPIRED_AT = CREATED_AT.plusMinutes(20);

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderExpirationPolicy expirationPolicy;

	@InjectMocks
	private OrderExpirationService orderExpirationService;

	@Nested
	@DisplayName("결제 대기 주문 만료 취소")
	class CancelPendingOrderByTimeout {

		@Test
		@DisplayName("20분이 지난 CREATED 주문은 FAILED 처리하고 기존 장바구니를 변경하지 않는다")
		void cancelPendingOrderByTimeout_expiredCreatedOrder_failsWithoutChangingCart() {
			// given
			Order order = createPendingOrderWithProducts();
			Cart cart = Cart.create(BUYER_ID);
			cart.addProduct(PRODUCT_ID_1);

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
			assertThat(order.getCanceledAt()).isEqualTo(EXPIRED_AT);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderProductStatus.FAILED);
			assertThat(cart.getCartProducts())
				.extracting(cartProduct -> cartProduct.getProductId())
				.containsExactly(PRODUCT_ID_1);
			then(orderRepository).should().findByIdWithOrderProductsForUpdate(order.getId());
		}

		@Test
		@DisplayName("장바구니가 없어도 새 장바구니를 생성하지 않고 주문만 만료 처리한다")
		void cancelPendingOrderByTimeout_withoutCart_doesNotCreateCart() {
			// given
			Order order = createPendingOrderWithProducts();

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		}

		@Test
		@DisplayName("20분이 지나지 않은 CREATED 주문은 상태 변경을 하지 않는다")
		void cancelPendingOrderByTimeout_notExpiredPendingOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));
			given(expirationPolicy.paymentTimeoutMinutes())
				.willReturn(20);

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT.minusMinutes(1));

			// then
			assertThat(completed).isFalse();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		}

		@Test
		@DisplayName("이미 PAID 상태인 주문은 상태 변경을 하지 않는다")
		void cancelPendingOrderByTimeout_paidOrder_doNothing() {
			// given
			Order order = createPaidOrderWithProducts();

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		}

		@Test
		@DisplayName("FAILED 상태인 주문은 상태 변경을 하지 않는다")
		void cancelPendingOrderByTimeout_failedOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.FAILED);

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		}

		@Test
		@DisplayName("CANCELED 상태인 주문은 상태 변경을 하지 않는다")
		void cancelPendingOrderByTimeout_canceledOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.CANCELED);

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
		}

		@Test
		@DisplayName("REFUNDED 상태인 주문은 상태 변경을 하지 않는다")
		void cancelPendingOrderByTimeout_refundedOrder_doNothing() {
			// given
			Order order = createPendingOrderWithProducts();
			order.updateOrderStatus(OrderStatus.REFUNDED);

			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
				.willReturn(Optional.of(order));

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(order.getId(), EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
		}

		@Test
		@DisplayName("Redis에 남은 주문 ID가 DB에 없으면 아무 작업도 하지 않는다")
		void cancelPendingOrderByTimeout_orderNotFound_doNothing() {
			// given
			given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_ID))
				.willReturn(Optional.empty());

			// when
			boolean completed = orderExpirationService.cancelPendingOrderByTimeout(ORDER_ID, EXPIRED_AT);

			// then
			assertThat(completed).isTrue();
		}
	}
}
