package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class OrderAdapterTest {

	@Mock
	private OrderPersistence orderPersistence;

	@Mock
	private OrderProductPersistence orderProductPersistence;

	@InjectMocks
	private OrderAdapter orderAdapter;

	@Test
	@DisplayName("주문 Root, UUID 순 주문상품, 초기화 Aggregate 순서로 조회한다")
	void findByIdWithOrderProductsForUpdate_locksRootThenChildrenAndFetchesAggregate() {
		Order order = createdOrder();
		given(orderPersistence.findByIdForUpdate(ORDER_A)).willReturn(Optional.of(order));
		given(orderPersistence.findByIdWithOrderProducts(ORDER_A)).willReturn(Optional.of(order));

		Optional<Order> result = orderAdapter.findByIdWithOrderProductsForUpdate(ORDER_A);

		assertThat(result).containsSame(order);
		InOrder inOrder = inOrder(orderPersistence, orderProductPersistence);
		inOrder.verify(orderPersistence).findByIdForUpdate(ORDER_A);
		inOrder.verify(orderProductPersistence).findAllByOrderIdForUpdate(ORDER_A);
		inOrder.verify(orderPersistence).findByIdWithOrderProducts(ORDER_A);
	}

	@Test
	@DisplayName("주문 Root가 없으면 주문상품과 Aggregate를 추가 조회하지 않는다")
	void findByIdWithOrderProductsForUpdate_missingRootReturnsEmpty() {
		given(orderPersistence.findByIdForUpdate(ORDER_A)).willReturn(Optional.empty());

		assertThat(orderAdapter.findByIdWithOrderProductsForUpdate(ORDER_A)).isEmpty();

		then(orderProductPersistence).shouldHaveNoInteractions();
		then(orderPersistence).should().findByIdForUpdate(ORDER_A);
		then(orderPersistence).shouldHaveNoMoreInteractions();
	}
}
