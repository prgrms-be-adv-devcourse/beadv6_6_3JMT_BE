package com.prompthub.order.infra.persistence.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class OrderAdapterTest {

	private static final UUID ORDER_A =
		UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID ORDER_B =
		UUID.fromString("00000000-0000-0000-0000-000000000502");

	@Mock
	private OrderPersistence orderPersistence;

	@Mock
	private OrderProductPersistence orderProductPersistence;

	@InjectMocks
	private OrderAdapter orderAdapter;

	@Test
	@DisplayName("주문 Root와 주문상품을 UUID 순서로 잠근다")
	void findAllByIdsWithOrderProductsForUpdate_locksDeterministically() {
		List<UUID> requestedIds = List.of(ORDER_B, ORDER_A, ORDER_B);
		given(orderPersistence.findAllByIdsWithOrderProducts(List.of(ORDER_A, ORDER_B)))
			.willReturn(List.of());

		orderAdapter.findAllByIdsWithOrderProductsForUpdate(requestedIds);

		InOrder inOrder = inOrder(orderPersistence, orderProductPersistence);
		inOrder.verify(orderPersistence).findByIdForUpdate(ORDER_A);
		inOrder.verify(orderPersistence).findByIdForUpdate(ORDER_B);
		inOrder.verify(orderProductPersistence).findAllByOrderIdForUpdate(ORDER_A);
		inOrder.verify(orderProductPersistence).findAllByOrderIdForUpdate(ORDER_B);
		inOrder.verify(orderPersistence).findAllByIdsWithOrderProducts(List.of(ORDER_A, ORDER_B));
	}
}
