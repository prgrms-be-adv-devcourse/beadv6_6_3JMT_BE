package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.prompthub.common.event.EventMessage;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CreateOrderCommandHandlerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private ProductClient productClient;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Spy
    private OrderPolicyService orderPolicyService;

    @Mock
    private com.prompthub.order.application.service.event.order.OrderEventMessageFactory orderEventMessageFactory;

    @Mock
    private com.prompthub.order.application.service.event.outbox.OutboxEventAppender outboxEventAppender;

    @InjectMocks
    private CreateOrderCommandHandler createOrderCommandHandler;

    @Nested
    @DisplayName("주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("상품 ID 목록이 유효하면 주문을 생성한다")
        void createOrder_validProductIds_success() {
            // given
            CreateOrderRequest request = createOrderRequest();
            List<ProductOrderSnapshot> productSnapshots = createProductSnapshots();
            Cart cart = Cart.create(BUYER_ID);
            cart.addProduct(PRODUCT_ID_1);
            cart.addProduct(PRODUCT_ID_2);

            given(productClient.getOrderSnapshots(request.productIds()))
                .willReturn(productSnapshots);

            given(orderNumberGenerator.generate())
                .willReturn(ORDER_NUMBER);

            given(orderRepository.save(any(Order.class)))
                .willAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now());
                    return order;
                });
            given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
                .willReturn(Optional.of(cart));

            given(orderEventMessageFactory.createOrderCreatedMessage(any(), any()))
                .willReturn(new EventMessage<>(UUID.randomUUID(), "ORDER_CREATED", LocalDateTime.now(), "ORDER", UUID.randomUUID(), null));

            // when
            CreateOrderResponse response = createOrderCommandHandler.createOrder(BUYER_ID, request);

            // then
            assertThat(response.orderId()).isNotNull();
            assertThat(response.orderNumber()).isEqualTo(ORDER_NUMBER);
            assertThat(response.buyerId()).isEqualTo(BUYER_ID);
            assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.totalAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(response.products()).hasSize(TOTAL_ITEM_COUNT);
            assertThat(response.createdAt()).isNotNull();
            assertThat(response.canceledAt()).isNull();

            assertThat(response.products())
                .extracting(OrderProductsResponse::productId)
                .containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);

            assertThat(response.products())
                .extracting(OrderProductsResponse::productAmountSnapshot)
                .containsExactly(PRODUCT_AMOUNT_1, PRODUCT_AMOUNT_2);

            assertThat(response.products())
                .extracting(OrderProductsResponse::orderStatus)
				.containsOnly(OrderStatus.PENDING);

            then(productClient).should().getOrderSnapshots(request.productIds());
            then(orderNumberGenerator).should().generate();
            then(orderRepository).should().save(any(Order.class));
            assertThat(cart.getCartProducts()).isEmpty();
            then(cartRepository).should().save(cart);

            ArgumentCaptor<OrderCreatedPayload> payloadCaptor = ArgumentCaptor.forClass(OrderCreatedPayload.class);
            then(orderEventMessageFactory).should().createOrderCreatedMessage(any(), payloadCaptor.capture());
            OrderCreatedPayload capturedPayload = payloadCaptor.getValue();
            assertThat(capturedPayload.orderId()).isNotNull();
            assertThat(capturedPayload.buyerId()).isEqualTo(BUYER_ID);
            assertThat(capturedPayload.totalAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(capturedPayload.createdAt()).isNotNull();

            then(outboxEventAppender).should().append(any());
            then(applicationEventPublisher).should().publishEvent(any(OrderCreatedEvent.class));
        }

        @Test
        @DisplayName("주문 생성 시 Order와 OrderProduct가 올바르게 생성되어 저장된다")
        void createOrder_captureSavedOrder_success() {
            // given
            CreateOrderRequest request = createOrderRequest();
            List<ProductOrderSnapshot> productSnapshots = createProductSnapshots();

            given(productClient.getOrderSnapshots(request.productIds()))
                .willReturn(productSnapshots);

            given(orderNumberGenerator.generate())
                .willReturn(ORDER_NUMBER);

            given(orderRepository.save(any(Order.class)))
                .willAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now());
                    return order;
                });
            given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
                .willReturn(Optional.empty());

            given(orderEventMessageFactory.createOrderCreatedMessage(any(), any()))
                .willReturn(new EventMessage<>(UUID.randomUUID(), "ORDER_CREATED", LocalDateTime.now(), "ORDER", UUID.randomUUID(), null));

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);

            // when
            createOrderCommandHandler.createOrder(BUYER_ID, request);

            // then
            then(orderRepository).should().save(orderCaptor.capture());

            Order savedOrder = orderCaptor.getValue();

            assertThat(savedOrder.getId()).isNotNull();
            assertThat(savedOrder.getBuyerId()).isEqualTo(BUYER_ID);
            assertThat(savedOrder.getOrderNumber()).isEqualTo(ORDER_NUMBER);
            assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(savedOrder.getTotalOrderAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(savedOrder.getTotalProductCount()).isEqualTo(TOTAL_ITEM_COUNT);
            assertThat(savedOrder.getOrderProducts()).hasSize(TOTAL_ITEM_COUNT);

            assertThat(savedOrder.getOrderProducts())
                .extracting(OrderProduct::getProductId)
                .containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);

            assertThat(savedOrder.getOrderProducts())
                .extracting(OrderProduct::getSellerId)
                .containsExactly(SELLER_ID_1, SELLER_ID_2);

            assertThat(savedOrder.getOrderProducts())
                .extracting(OrderProduct::getProductAmount)
                .containsExactly(PRODUCT_AMOUNT_1, PRODUCT_AMOUNT_2);

            assertThat(savedOrder.getOrderProducts())
                .extracting(OrderProduct::getOrderProductStatus)
                .containsOnly(OrderStatus.PENDING);

            assertThat(savedOrder.getOrderProducts())
                .allSatisfy(orderProduct ->
                    assertThat(orderProduct.getOrder()).isSameAs(savedOrder)
                );

            then(outboxEventAppender).should().append(any());
        }

        @Test
        @DisplayName("상품 ID 목록이 null이면 주문 생성에 실패한다")
        void createOrder_nullProductIds_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequestWithNullProductIds();

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should(never()).getOrderSnapshots(anyList());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("상품 ID 목록이 비어 있으면 주문 생성에 실패한다")
        void createOrder_emptyProductIds_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequestWithEmptyProductIds();

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should(never()).getOrderSnapshots(anyList());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("중복된 상품 ID가 있으면 주문 생성에 실패한다")
        void createOrder_duplicatedProductIds_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequestWithDuplicatedProductIds();

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should(never()).getOrderSnapshots(anyList());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("상품 스냅샷 응답이 null이면 주문 생성에 실패한다")
        void createOrder_nullProductSnapshots_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequest();

            given(productClient.getOrderSnapshots(request.productIds()))
                .willReturn(null);

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should().getOrderSnapshots(request.productIds());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("요청 상품 수와 조회된 상품 수가 다르면 주문 생성에 실패한다")
        void createOrder_productSnapshotSizeMismatch_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequest();

            List<ProductOrderSnapshot> snapshots = createSingleProductSnapshot();

            given(productClient.getOrderSnapshots(request.productIds()))
                .willReturn(snapshots);

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should().getOrderSnapshots(request.productIds());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("조회되지 않은 상품 ID가 있으면 주문 생성에 실패한다")
        void createOrder_missingProductSnapshot_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequest();

            List<ProductOrderSnapshot> snapshots = createProductSnapshotsWithUnknownProduct();

            given(productClient.getOrderSnapshots(request.productIds()))
                .willReturn(snapshots);

            // when & then
            assertThatThrownBy(() -> createOrderCommandHandler.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                );

            then(productClient).should().getOrderSnapshots(request.productIds());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }
    }


}
