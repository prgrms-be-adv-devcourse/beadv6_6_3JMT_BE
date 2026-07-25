package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderCreationItem;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_B;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_C;
import static com.prompthub.order.fixture.OrderV2Fixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderV2Fixture.orderItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class OrderCreatorTest {

	@Mock
	private OrderNumberGenerator orderNumberGenerator;

	@Mock
	private OrderProductReservationService reservationService;

	@Mock
	private OrderCreationTransactionService transactionService;

	@InjectMocks
	private OrderCreator orderCreator;

	@Test
	@DisplayName("Redis 예약 후 내부 주문 생성 트랜잭션을 실행한다")
	void create_reservesBeforeTransactionalCreation() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		CreateOrderResult expected = mock(CreateOrderResult.class);
		given(transactionService.create(any(Order.class))).willReturn(expected);

		CreateOrderResult actual = orderCreator.create(BUYER_ID, orderItems());

		assertThat(actual).isSameAs(expected);
		InOrder ordered = inOrder(reservationService, transactionService);
		ordered.verify(reservationService).reserve(any(Order.class));
		ordered.verify(transactionService).create(any(Order.class));
	}

	@Test
	@DisplayName("내부 트랜잭션 실패 시 예약을 해제하고 원래 실패를 보존한다")
	void create_transactionFailureReleasesReservationAndPreservesFailure() {
		RuntimeException failure = new RuntimeException("database failure");
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		given(transactionService.create(any(Order.class))).willThrow(failure);

		assertThatThrownBy(() -> orderCreator.create(BUYER_ID, orderItems()))
			.isSameAs(failure);

		then(reservationService).should().releaseAfterFailure(any(Order.class));
	}

	@Test
	@DisplayName("예약 실패 시 DB 트랜잭션을 시작하지 않는다")
	void create_reservationFailureDoesNotStartDatabaseTransaction() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		willThrow(new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED))
			.given(reservationService).reserve(any(Order.class));

		assertThatThrownBy(() -> orderCreator.create(BUYER_ID, orderItems()))
			.isInstanceOf(OrderException.class);

		then(transactionService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("여러 판매자의 상품으로 단일 주문 aggregate를 생성한다")
	void create_buildsSingleAggregateForMultipleSellers() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		given(transactionService.create(any(Order.class))).willReturn(mock(CreateOrderResult.class));

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(transactionService).should().create(orderCaptor.capture());
		Order order = orderCaptor.getValue();
		assertThat(order.getOrderNumber()).isEqualTo("ORD-A");
		assertThat(order.getBuyerId()).isEqualTo(BUYER_ID);
		assertThat(order.getTotalOrderAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getSellerId)
			.containsExactly(SELLER_A, SELLER_B, SELLER_A, SELLER_C);
		assertThat(order.getOrderProducts())
			.allSatisfy(product -> assertThat(product.getOrder()).isSameAs(order));
	}

	@Test
	@DisplayName("상품 총액이 int 최댓값이면 주문을 생성한다")
	void totalAmountAtIntMax_createsOrder() {
		given(orderNumberGenerator.generate()).willReturn("ORD-MAX");
		List<OrderCreationItem> items = List.of(
			new OrderCreationItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, Integer.MAX_VALUE - 1),
			new OrderCreationItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, 1)
		);
		given(transactionService.create(any(Order.class))).willAnswer(invocation ->
			CreateOrderResult.from(invocation.getArgument(0))
		);

		CreateOrderResult result = orderCreator.create(BUYER_ID, items);

		assertThat(result.totalAmount()).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	@DisplayName("상품 총액 overflow면 주문 번호·Redis·DB 부수효과를 만들지 않는다")
	void totalAmountOverflow_throwsStableOrderExceptionWithoutSideEffects() {
		List<OrderCreationItem> items = List.of(
			new OrderCreationItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, Integer.MAX_VALUE),
			new OrderCreationItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, 1)
		);

		assertThatThrownBy(() -> orderCreator.create(BUYER_ID, items))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);

		then(orderNumberGenerator).shouldHaveNoInteractions();
		then(reservationService).shouldHaveNoInteractions();
		then(transactionService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("무료 주문은 완료·결제 상태로 만든 뒤 예약하고 내부 트랜잭션을 실행한다")
	void freeOrder_completesBeforeReservationAndTransactionalCreation() {
		given(orderNumberGenerator.generate()).willReturn("ORD-FREE");
		given(transactionService.create(any(Order.class))).willReturn(mock(CreateOrderResult.class));
		List<OrderCreationItem> items = List.of(
			new OrderCreationItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, 0)
		);

		orderCreator.create(BUYER_ID, items);

		ArgumentCaptor<Order> reservationOrder = ArgumentCaptor.forClass(Order.class);
		ArgumentCaptor<Order> transactionOrder = ArgumentCaptor.forClass(Order.class);
		then(reservationService).should().reserve(reservationOrder.capture());
		then(transactionService).should().create(transactionOrder.capture());
		assertThat(transactionOrder.getValue()).isSameAs(reservationOrder.getValue());
		assertThat(transactionOrder.getValue().getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(transactionOrder.getValue().getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
	}
}
