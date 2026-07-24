package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderProductPurchasePolicyTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderProductReservationService reservationService;

	@Test
	@DisplayName("DB에 차단 주문 상품이 있으면 주문을 거절한다")
	void validateOrderable_blockingOrderProductThrowsConflict() {
		given(orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_A))
			.willReturn(true);
		OrderProductPurchasePolicy policy = new OrderProductPurchasePolicy(orderRepository, reservationService);

		assertThatThrownBy(() -> policy.validateOrderable(BUYER_ID, List.of(PRODUCT_A)))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(reservationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("차단 주문 상품과 예약이 모두 없으면 주문을 허용한다")
	void validateOrderable_withoutBlockingStatePasses() {
		given(orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_A))
			.willReturn(false);
		OrderProductPurchasePolicy policy = new OrderProductPurchasePolicy(orderRepository, reservationService);

		assertThatCode(() -> policy.validateOrderable(BUYER_ID, List.of(PRODUCT_A, PRODUCT_A)))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Redis에 결제 대기 예약이 있으면 장바구니 추가를 거절한다")
	void validateCartAddable_reservedProductThrowsConflict() {
		given(orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_A))
			.willReturn(false);
		given(reservationService.isReserved(BUYER_ID, PRODUCT_A)).willReturn(true);
		OrderProductPurchasePolicy policy = new OrderProductPurchasePolicy(orderRepository, reservationService);

		assertThatThrownBy(() -> policy.validateCartAddable(BUYER_ID, PRODUCT_A))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
	}

	@Test
	@DisplayName("DB 구매 이력이 있으면 Redis를 조회하지 않고 장바구니 추가를 거절한다")
	void validateCartAddable_blockingOrderProductThrowsBeforeRedis() {
		given(orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_A))
			.willReturn(true);
		OrderProductPurchasePolicy policy = new OrderProductPurchasePolicy(orderRepository, reservationService);

		assertThatThrownBy(() -> policy.validateCartAddable(BUYER_ID, PRODUCT_A))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(reservationService).should(never()).isReserved(BUYER_ID, PRODUCT_A);
	}
}
