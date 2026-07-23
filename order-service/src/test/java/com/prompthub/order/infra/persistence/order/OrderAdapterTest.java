package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

	@Test
	@DisplayName("상품 구매 차단 조회를 영속성 어댑터에 위임한다")
	void existsBlockingOrderProduct_delegatesToPersistence() {
		given(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(ORDER_A, ORDER_A))
			.willReturn(true);

		assertThat(orderAdapter.existsBlockingOrderProductByBuyerIdAndProductId(ORDER_A, ORDER_A)).isTrue();
		then(orderPersistence).should().existsBlockingOrderProductByBuyerIdAndProductId(ORDER_A, ORDER_A);
	}

	@Test
	@DisplayName("만료 주문 후보 조회를 영속성 어댑터에 위임한다")
	void findExpiredCreatedOrderIds_delegatesToPersistence() {
		LocalDateTime cutoff = LocalDateTime.of(2026, 7, 23, 12, 0);
		given(orderPersistence.findExpiredCreatedOrderIds(eq(cutoff), org.mockito.ArgumentMatchers.any()))
			.willReturn(List.of(ORDER_A));

		assertThat(orderAdapter.findExpiredCreatedOrderIds(cutoff, 10)).containsExactly(ORDER_A);
		then(orderPersistence).should().findExpiredCreatedOrderIds(
			eq(cutoff),
			argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 10)
		);
	}

	@Test
	void saveAndFlush_pendingProductConstraint_mapsToO018() {
		Order order = createdOrder();
		given(orderPersistence.saveAndFlush(order))
			.willThrow(integrityFailure("uk_order_product_buyer_product_pending"));

		assertThatThrownBy(() -> orderAdapter.saveAndFlush(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue(
				"errorCode",
				ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
			);
	}

	@Test
	void saveAndFlush_unrelatedConstraint_preservesOriginalFailure() {
		Order order = createdOrder();
		DataIntegrityViolationException failure = integrityFailure("uk_order_number");
		given(orderPersistence.saveAndFlush(order)).willThrow(failure);

		assertThatThrownBy(() -> orderAdapter.saveAndFlush(order))
			.isSameAs(failure);
	}

	private DataIntegrityViolationException integrityFailure(String constraintName) {
		ConstraintViolationException cause = new ConstraintViolationException(
			"constraint violation",
			new SQLException("duplicate"),
			"insert into order_product",
			ConstraintViolationException.ConstraintKind.UNIQUE,
			constraintName
		);
		return new DataIntegrityViolationException("constraint violation", cause);
	}
}
