package com.prompthub.order.application.service.order;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.UNKNOWN_PRODUCT;
import static com.prompthub.order.fixture.OrderV2Fixture.command;
import static com.prompthub.order.fixture.OrderV2Fixture.requestedProductIds;
import static com.prompthub.order.fixture.OrderV2Fixture.result;
import static com.prompthub.order.fixture.OrderV2Fixture.shuffledSnapshots;
import static com.prompthub.order.fixture.OrderV2Fixture.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderCommandHandlerTest {

	@Mock
	private ProductClient productClient;

	@Mock
	private OrderCreator orderCreator;

	@Spy
	private OrderPolicyService orderPolicyService;

	@InjectMocks
	private OrderCommandHandler orderCommandHandler;

	@Test
	@DisplayName("snapshot 순서와 무관하게 productId로 결합하고 요청 제목을 유지한다")
	@SuppressWarnings("unchecked")
	void createOrderCombinesRequestAndSnapshotByProductId() {
		CreateOrderResult expected = result();
		given(productClient.getOrderSnapshots(requestedProductIds()))
			.willReturn(shuffledSnapshots());
		given(orderCreator.create(eq(BUYER_ID), anyList()))
			.willReturn(expected);

		CreateOrderResult actual = orderCommandHandler.createOrder(BUYER_ID, command());

		assertThat(actual).isSameAs(expected);
		then(productClient).should().getOrderSnapshots(requestedProductIds());
		ArgumentCaptor<List<OrderItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
		then(orderCreator).should().create(eq(BUYER_ID), itemsCaptor.capture());

		assertThat(itemsCaptor.getValue())
			.extracting(OrderItem::productId)
			.containsExactly(PRODUCT_A1, PRODUCT_B1, PRODUCT_A2,
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1);
		assertThat(itemsCaptor.getValue())
			.extracting(OrderItem::productTitle)
			.containsExactly(
				REQUEST_TITLE_A1,
				com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_B1,
				REQUEST_TITLE_A2,
				com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_C1
			);
		assertThat(itemsCaptor.getValue().getFirst().sellerId()).isEqualTo(SELLER_A);
		assertThat(itemsCaptor.getValue().getFirst().amount()).isEqualTo(AMOUNT_A1);
		assertThat(itemsCaptor.getValue().getFirst().productTitle()).isNotEqualTo("서버-A1");
	}

	@Test
	@DisplayName("상품 ID가 중복되면 Product Service 호출 전에 실패한다")
	void duplicateProductIdFailsBeforeRemoteCall() {
		CreateOrderCommand duplicate = new CreateOrderCommand(List.of(
			new CreateOrderCommand.Product(PRODUCT_A1, REQUEST_TITLE_A1),
			new CreateOrderCommand.Product(PRODUCT_A1, "다른 제목")
		));

		assertInvalidInput(() -> orderCommandHandler.createOrder(BUYER_ID, duplicate));

		then(productClient).shouldHaveNoInteractions();
		then(orderCreator).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("빈 상품 목록은 외부 호출과 저장을 수행하지 않는다")
	void emptyProductsFailBeforeRemoteCall() {
		CreateOrderCommand empty = new CreateOrderCommand(List.of());

		assertInvalidInput(() -> orderCommandHandler.createOrder(BUYER_ID, empty));

		then(productClient).shouldHaveNoInteractions();
		then(orderCreator).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("공백 제목은 외부 호출과 저장을 수행하지 않는다")
	void blankTitleFailsBeforeRemoteCall() {
		CreateOrderCommand blankTitle = new CreateOrderCommand(List.of(
			new CreateOrderCommand.Product(PRODUCT_A1, "   ")
		));

		assertInvalidInput(() -> orderCommandHandler.createOrder(BUYER_ID, blankTitle));

		then(productClient).shouldHaveNoInteractions();
		then(orderCreator).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Product Service가 null을 반환하면 저장하지 않는다")
	void nullSnapshotsDoNotCreateOrders() {
		given(productClient.getOrderSnapshots(requestedProductIds())).willReturn(null);

		assertInvalidInput(() -> orderCommandHandler.createOrder(BUYER_ID, command()));

		then(orderCreator).shouldHaveNoInteractions();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidSnapshots")
	@DisplayName("유효하지 않은 snapshot은 저장 전에 거부한다")
	void invalidSnapshotDoesNotCreateOrders(String description, List<ProductOrderSnapshot> snapshots) {
		given(productClient.getOrderSnapshots(requestedProductIds())).willReturn(snapshots);

		assertInvalidInput(() -> orderCommandHandler.createOrder(BUYER_ID, command()));

		then(orderCreator).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Product Service 장애는 원본 예외를 보존하고 저장하지 않는다")
	void productServiceFailureIsPreserved() {
		BusinessException failure = new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		given(productClient.getOrderSnapshots(requestedProductIds())).willThrow(failure);

		assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, command()))
			.isSameAs(failure);
		then(orderCreator).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("OrderCommandHandler는 CartRepository에 의존하지 않는다")
	void handlerHasNoCartRepositoryDependency() {
		assertThat(Arrays.stream(OrderCommandHandler.class.getDeclaredFields())
			.map(java.lang.reflect.Field::getType))
			.doesNotContain(CartRepository.class);
	}

	private static Stream<Arguments> invalidSnapshots() {
		List<ProductOrderSnapshot> missing = shuffledSnapshots().subList(0, 3);
		List<ProductOrderSnapshot> unknown = List.of(
			snapshot(
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1,
				com.prompthub.order.fixture.OrderV2Fixture.SELLER_C,
				"서버-C1",
				com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_C1
			),
			snapshot(PRODUCT_A2, SELLER_A, "서버-A2", 2_200),
			snapshot(PRODUCT_B1, com.prompthub.order.fixture.OrderV2Fixture.SELLER_B, "서버-B1", 3_300),
			snapshot(UNKNOWN_PRODUCT, SELLER_A, "알 수 없음", AMOUNT_A1)
		);
		List<ProductOrderSnapshot> duplicated = List.of(
			snapshot(PRODUCT_A1, SELLER_A, "서버-A1", AMOUNT_A1),
			snapshot(PRODUCT_A1, SELLER_A, "서버-A1 중복", AMOUNT_A1),
			snapshot(PRODUCT_B1, com.prompthub.order.fixture.OrderV2Fixture.SELLER_B, "서버-B1", 3_300),
			snapshot(
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1,
				com.prompthub.order.fixture.OrderV2Fixture.SELLER_C,
				"서버-C1",
				com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_C1
			)
		);
		List<ProductOrderSnapshot> nullSeller = List.of(
			snapshot(PRODUCT_A1, null, "서버-A1", AMOUNT_A1),
			shuffledSnapshots().get(0),
			shuffledSnapshots().get(1),
			shuffledSnapshots().get(2)
		);
		List<ProductOrderSnapshot> negativeAmount = List.of(
			snapshot(PRODUCT_A1, SELLER_A, "서버-A1", -1),
			shuffledSnapshots().get(0),
			shuffledSnapshots().get(1),
			shuffledSnapshots().get(2)
		);
		List<ProductOrderSnapshot> zeroAmount = List.of(
			snapshot(PRODUCT_A1, SELLER_A, "서버-A1", 0),
			shuffledSnapshots().get(0),
			shuffledSnapshots().get(1),
			shuffledSnapshots().get(2)
		);

		return Stream.of(
			Arguments.of("snapshot count mismatch", missing),
			Arguments.of("unknown product id", unknown),
			Arguments.of("duplicate snapshot", duplicated),
			Arguments.of("null seller", nullSeller),
			Arguments.of("negative amount", negativeAmount),
			Arguments.of("zero amount", zeroAmount)
		);
	}

	private void assertInvalidInput(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
		assertThatThrownBy(callable)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
	}
}
