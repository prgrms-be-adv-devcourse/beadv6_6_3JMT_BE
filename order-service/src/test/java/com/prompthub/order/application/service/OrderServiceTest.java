package com.prompthub.order.application.service;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.domain.enums.PaymentStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private ProductClient productClient;

    @Spy
    private OrderPolicyService orderPolicyService;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("구매 상품 콘텐츠 열람")
    class GetOrderContent {

        private static final String PRODUCT_CONTENT = "구매 후 확인 가능한 프롬프트 원문";

        @Test
        @DisplayName("결제 완료된 본인 주문상품이면 콘텐츠를 반환하고 다운로드 처리한다")
        void getOrderContent_paidOwnerOrderProduct_success() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willReturn(new ProductContent(orderProduct.getProductId(), PRODUCT_CONTENT));

            // when
            OrderContentResponse response = orderService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.orderId()).isEqualTo(order.getId());
            assertThat(response.orderProductId()).isEqualTo(orderProduct.getId());
            assertThat(response.orderNumber()).isEqualTo(ORDER_NUMBER);
            assertThat(response.productId()).isEqualTo(orderProduct.getProductId());
            assertThat(response.isDownload()).isTrue();
            assertThat(response.productTitle()).isEqualTo(PRODUCT_TITLE_1);
            assertThat(response.content()).isEqualTo(PRODUCT_CONTENT);
            assertThat(orderProduct.isDownload()).isTrue();

            then(orderRepository).should().findByIdWithOrderProducts(order.getId());
            then(productClient).should().getProductContent(orderProduct.getProductId());
        }

        @Test
        @DisplayName("이미 열람한 주문상품도 다시 콘텐츠를 조회할 수 있다")
        void getOrderContent_alreadyDownloaded_success() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            orderProduct.markDownloaded();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willReturn(new ProductContent(orderProduct.getProductId(), PRODUCT_CONTENT));

            // when
            OrderContentResponse response = orderService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.isDownload()).isTrue();
            assertThat(response.content()).isEqualTo(PRODUCT_CONTENT);
            assertThat(orderProduct.isDownload()).isTrue();

            then(productClient).should().getProductContent(orderProduct.getProductId());
        }

        @Test
        @DisplayName("주문이 없으면 O001 예외가 발생한다")
        void getOrderContent_orderNotFound_throwsException() {
            // given
            given(orderRepository.findByIdWithOrderProducts(ORDER_ID))
                .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.getOrderContent(BUYER_ID, ORDER_ID, ORDER_PRODUCT_ID))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );

            then(productClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("본인 주문이 아니면 A004 예외가 발생한다")
        void getOrderContent_notOwner_throwsException() {
            // given
            UUID otherBuyerId = UUID.fromString("00000000-0000-0000-0000-000000000991");
            Order order = createPaidOrderWithProducts();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrderContent(otherBuyerId, order.getId(), order.getOrderProducts().getFirst().getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN)
                );

            then(productClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("주문 상태가 PAID가 아니면 E001 예외가 발생한다")
        void getOrderContent_notPaidOrder_throwsException() {
            // given
            Order order = createPendingOrderWithProducts();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrderContent(BUYER_ID, order.getId(), order.getOrderProducts().getFirst().getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("주문상품이 해당 주문에 포함되지 않으면 E001 예외가 발생한다")
        void getOrderContent_orderProductNotIncluded_throwsException() {
            // given
            Order order = createPaidOrderWithProducts();
            UUID otherOrderProductId = UUID.fromString("00000000-0000-0000-0000-000000000699");

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrderContent(BUYER_ID, order.getId(), otherOrderProductId))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("주문상품 상태가 PAID가 아니면 E001 예외가 발생한다")
        void getOrderContent_notPaidOrderProduct_throwsException() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            ReflectionTestUtils.setField(orderProduct, "orderStatus", OrderStatus.REFUNDED);

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("내 주문 상세 조회")
    class GetOrderDetail {

        @Test
        @DisplayName("본인 주문이면 주문 기본 정보와 주문상품 목록을 반환한다")
        void getOrderDetail_ownerOrder_success() {
            // given
            Order order = createPaidOrderWithProducts();
            order.getOrderProducts().getFirst().markDownloaded();
            ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when
            OrderDetailResponse response = orderService.getOrderDetail(BUYER_ID, order.getId());

            // then
            assertThat(response.orderId()).isEqualTo(order.getId());
            assertThat(response.orderNumber()).isEqualTo(ORDER_NUMBER);
            assertThat(response.buyerId()).isEqualTo(BUYER_ID);
            assertThat(response.orderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(response.totalAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(response.totalProductCount()).isEqualTo(TOTAL_ITEM_COUNT);
            assertThat(response.paidAt()).isNotNull();
            assertThat(response.canceledAt()).isNull();
            assertThat(response.refundedAt()).isNull();
            assertThat(response.createdAt()).isEqualTo(CREATED_AT);
            assertThat(response.hasDownloadProduct()).isTrue();
            assertThat(response.products()).hasSize(TOTAL_ITEM_COUNT);
            assertThat(response.products().getFirst().productId()).isEqualTo(PRODUCT_ID_1);
            assertThat(response.products().getFirst().isContentAccessible()).isTrue();
            assertThat(response.products().getFirst().download()).isTrue();

            then(orderRepository).should().findByIdWithOrderProducts(order.getId());
        }

        @Test
        @DisplayName("주문이 없으면 O001 예외가 발생한다")
        void getOrderDetail_orderNotFound_throwsException() {
            // given
            given(orderRepository.findByIdWithOrderProducts(ORDER_ID))
                .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.getOrderDetail(BUYER_ID, ORDER_ID))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );
        }

        @Test
        @DisplayName("본인 주문이 아니면 A004 예외가 발생한다")
        void getOrderDetail_notOwner_throwsException() {
            // given
            UUID otherBuyerId = UUID.fromString("00000000-0000-0000-0000-000000000991");
            Order order = createPendingOrderWithProducts();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrderDetail(otherBuyerId, order.getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN)
                );
        }

        @Test
        @DisplayName("결제 완료 주문상품만 콘텐츠 열람 가능하다")
        void getOrderDetail_contentAccessibleByOrderProductStatus_success() {
            // given
            Order order = createPendingOrderWithProducts();
            OrderProduct paidProduct = order.getOrderProducts().getFirst();
            OrderProduct refundedProduct = order.getOrderProducts().get(1);
            ReflectionTestUtils.setField(paidProduct, "orderStatus", OrderStatus.PAID);
            ReflectionTestUtils.setField(refundedProduct, "orderStatus", OrderStatus.REFUNDED);

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when
            OrderDetailResponse response = orderService.getOrderDetail(BUYER_ID, order.getId());

            // then
            assertThat(response.products())
                .extracting(product -> product.isContentAccessible())
                .containsExactly(true, false);
        }
    }

    @Nested
    @DisplayName("주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("상품 ID 목록이 유효하면 주문을 생성한다")
        void createOrder_validProductIds_success() {
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

            // when
            CreateOrderResponse response = orderService.createOrder(BUYER_ID, request);

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

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);

            // when
            orderService.createOrder(BUYER_ID, request);

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
                .extracting(OrderProduct::getOrderStatus)
                .containsOnly(OrderStatus.PENDING);

            assertThat(savedOrder.getOrderProducts())
                .allSatisfy(orderProduct ->
                    assertThat(orderProduct.getOrder()).isSameAs(savedOrder)
                );
        }

        @Test
        @DisplayName("상품 ID 목록이 null이면 주문 생성에 실패한다")
        void createOrder_nullProductIds_throwsException() {
            // given
            CreateOrderRequest request = createOrderRequestWithNullProductIds();

            // when & then
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage(ErrorCode.INVALID_INPUT_VALUE.getMessage());

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
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage(ErrorCode.INVALID_INPUT_VALUE.getMessage());

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
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage(ErrorCode.INVALID_INPUT_VALUE.getMessage());

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
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage("주문 가능한 상품 정보가 올바르지 않습니다.");

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
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage("주문 가능한 상품 정보가 올바르지 않습니다.");

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
            assertThatThrownBy(() -> orderService.createOrder(BUYER_ID, request))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
                )
                .hasMessage("조회되지 않은 상품이 포함되어 있습니다.");

            then(productClient).should().getOrderSnapshots(request.productIds());
            then(orderNumberGenerator).should(never()).generate();
            then(orderRepository).should(never()).save(any(Order.class));
        }
    }

    @Nested
    @DisplayName("결제 승인 이벤트 처리")
    class ApproveOrder {

        @Test
        @DisplayName("결제 승인 이벤트를 받으면 주문을 PAID 상태로 변경하고 장바구니 상품을 제거한다")
        void approveOrder_paymentApproved_success() {
            // given
            Order order = createPendingOrderWithProducts();
            PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);
            Cart cart = mock(Cart.class);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.of(order));

            given(orderPaymentRepository.existsByOrderId(event.orderId()))
                .willReturn(false);

            given(cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId()))
                .willReturn(Optional.of(cart));

            ArgumentCaptor<OrderPayment> paymentCaptor = ArgumentCaptor.forClass(OrderPayment.class);

            // when
            orderService.approveOrder(event);

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getPaidAt()).isEqualTo(APPROVED_OFFSET_AT.toLocalDateTime());

            assertThat(order.getOrderProducts())
                .extracting(OrderProduct::getOrderStatus)
                .containsOnly(OrderStatus.PAID);

            then(orderPaymentRepository).should().save(paymentCaptor.capture());
            OrderPayment savedPayment = paymentCaptor.getValue();
            assertThat(savedPayment.getOrderId()).isEqualTo(order.getId());
            assertThat(savedPayment.getPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(savedPayment.getBuyerId()).isEqualTo(BUYER_ID);
            assertThat(savedPayment.getPgTxId()).isEqualTo(PG_TX_ID);
            assertThat(savedPayment.getPaymentMethod()).isEqualTo(PAYMENT_METHOD);
            assertThat(savedPayment.getProvider()).isEqualTo(PAYMENT_PROVIDER);
            assertThat(savedPayment.getApprovedAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(savedPayment.getApprovedAt()).isEqualTo(APPROVED_OFFSET_AT);

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(orderPaymentRepository).should().existsByOrderId(event.orderId());
            then(cartRepository).should().findByBuyerIdWithCartProducts(order.getBuyerId());
            then(cart).should().removeProductsByProductIds(productIds());
        }

        @Test
        @DisplayName("결제 승인 후 장바구니가 없어도 주문 결제 완료 처리는 성공한다")
        void approveOrder_cartNotFound_success() {
            // given
            Order order = createPendingOrderWithProducts();
            PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.of(order));

            given(orderPaymentRepository.existsByOrderId(event.orderId()))
                .willReturn(false);

            given(cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId()))
                .willReturn(Optional.empty());

            // when
            orderService.approveOrder(event);

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);

            assertThat(order.getOrderProducts())
                .extracting(OrderProduct::getOrderStatus)
                .containsOnly(OrderStatus.PAID);

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(orderPaymentRepository).should().save(any(OrderPayment.class));
            then(cartRepository).should().findByBuyerIdWithCartProducts(order.getBuyerId());
        }

        @Test
        @DisplayName("이미 결제 내역이 있고 결제 완료된 주문이면 결제 승인 이벤트를 무시한다")
        void approveOrder_alreadyPaidWithOrderPayment_doNothing() {
            // given
            Order order = createPendingOrderWithProducts();
            order.markPaid();

            PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.of(order));

            given(orderPaymentRepository.existsByOrderId(event.orderId()))
                .willReturn(true);

            // when
            orderService.approveOrder(event);

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(orderPaymentRepository).should().existsByOrderId(event.orderId());
            then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
            then(cartRepository).should(never()).findByBuyerIdWithCartProducts(any(UUID.class));
        }

        @Test
        @DisplayName("결제 승인 이벤트의 주문 ID가 존재하지 않으면 예외가 발생한다")
        void approveOrder_orderNotFound_throwsException() {
            // given
            UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000009999");
            PaymentApprovedEvent event = createPaymentApprovedEvent(orderId, TOTAL_AMOUNT);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.approveOrder(event))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(cartRepository).should(never()).findByBuyerIdWithCartProducts(any(UUID.class));
        }

        @Test
        @DisplayName("주문 상태가 PENDING이 아니면 결제 승인 처리에 실패한다")
        void approveOrder_notPending_throwsException() {
            // given
            Order order = createPendingOrderWithProducts();
            order.updateOrderStatus(OrderStatus.CANCELED);

            PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.of(order));

            given(orderPaymentRepository.existsByOrderId(event.orderId()))
                .willReturn(false);

            // when & then
            assertThatThrownBy(() -> orderService.approveOrder(event))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_PAYMENT_STATUS_INVALID)
                );

            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
            then(cartRepository).should(never()).findByBuyerIdWithCartProducts(any(UUID.class));
        }

        @Test
        @DisplayName("결제 승인 금액과 주문 금액이 다르면 결제 승인 처리에 실패한다")
        void approveOrder_amountMismatch_throwsException() {
            // given
            Order order = createPendingOrderWithProducts();
            PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), PRODUCT_AMOUNT_2);

            given(orderRepository.findByIdWithOrderProducts(event.orderId()))
                .willReturn(Optional.of(order));

            given(orderPaymentRepository.existsByOrderId(event.orderId()))
                .willReturn(false);

            // when & then
            assertThatThrownBy(() -> orderService.approveOrder(event))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH)
                );

            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

            assertThat(order.getOrderProducts())
                .extracting(OrderProduct::getOrderStatus)
                .containsOnly(OrderStatus.PENDING);

            then(orderRepository).should().findByIdWithOrderProducts(event.orderId());
            then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
            then(cartRepository).should(never()).findByBuyerIdWithCartProducts(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("내 주문 목록 조회")
    class GetMyOrders {

        @Test
        @DisplayName("page와 size만 전달하면 구매자 기준 최신 주문상품 목록을 반환한다")
        void getMyOrders_defaultFilters_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.PAID,
                OrderStatus.PAID,
                false,
                4.5
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(
                BUYER_ID,
                null,
                null,
                null,
                pageable
            )).willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getNumber()).isZero();
            assertThat(response.getSize()).isEqualTo(20);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.hasNext()).isFalse();

            OrderListResponse order = response.getContent().getFirst();
            assertThat(order.orderId()).isEqualTo(ORDER_ID);
            assertThat(order.orderProductId()).isEqualTo(ORDER_PRODUCT_ID);
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isRefund()).isTrue();
            assertThat(order.productType()).isEqualTo(PRODUCT_TYPE_PROMPT);
            assertThat(order.title()).isEqualTo(PRODUCT_TITLE_1);
            assertThat(order.model()).isEqualTo(PRODUCT_MODEL);
            assertThat(order.rating()).isEqualTo(4.5);
            assertThat(order.paidAt()).isEqualTo(PAID_AT);
            assertThat(order.createdAt()).isEqualTo(CREATED_AT);

            then(orderRepository).should().searchOrderproducts(
                BUYER_ID,
                null,
                null,
                null,
                pageable
            );
        }

        @Test
        @DisplayName("status 필터는 Repository 조회 조건으로 전달한다")
        void getMyOrders_statusFilter_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, OrderStatus.PAID, null, null);
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(
                BUYER_ID,
                OrderStatus.PAID,
                null,
                null,
                pageable
            )).willReturn(new PageImpl<>(List.of(), pageable, 0));

            // when
            orderService.getOrders(BUYER_ID, request);

            // then
            then(orderRepository).should().searchOrderproducts(
                BUYER_ID,
                OrderStatus.PAID,
                null,
                null,
                pageable
            );
        }

        @Test
        @DisplayName("from과 to는 하루 시작/끝 시각으로 변환해 Repository에 전달한다")
        void getMyOrders_dateRange_success() {
            // given
            PageRequestParams request = new PageRequestParams(
                1,
                20,
                null,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
            );
            LocalDateTime from = LocalDateTime.of(2026, 6, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2026, 6, 30, 23, 59, 59);
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(
                BUYER_ID,
                null,
                from,
                to,
                pageable
            )).willReturn(new PageImpl<>(List.of(), pageable, 0));

            // when
            orderService.getOrders(BUYER_ID, request);

            // then
            then(orderRepository).should().searchOrderproducts(
                BUYER_ID,
                null,
                from,
                to,
                pageable
            );
        }

        @Test
        @DisplayName("리뷰 평점이 없으면 rating은 null이다")
        void getMyOrders_withoutRating_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.PAID,
                OrderStatus.PAID,
                false,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().rating()).isNull();
        }

        @Test
        @DisplayName("다운로드한 상품은 환불 가능하지 않다")
        void getMyOrders_downloadedProduct_notRefundable() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.PAID,
                OrderStatus.PAID,
                true,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().isRefund()).isFalse();
        }

        @Test
        @DisplayName("PAID가 아닌 주문은 환불 가능하지 않다")
        void getMyOrders_notPaidOrder_notRefundable() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.CANCELED,
                OrderStatus.CANCELED,
                false,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().isRefund()).isFalse();
        }

        @Test
        @DisplayName("조회 결과가 없으면 빈 목록과 total 0을 반환한다")
        void getMyOrders_emptyResult_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

            // when
            Page<OrderListResponse> response = orderService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent()).isEmpty();
            assertThat(response.getTotalElements()).isZero();
            assertThat(response.hasNext()).isFalse();
        }

    }

    @Nested
    @DisplayName("내 결제 내역 조회")
    class GetMyOrderPayments {

        @Test
        @DisplayName("page와 size가 전달되면 구매자 결제 내역을 반환한다")
        void getMyOrderPayments_defaultPage_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderPaymentListProjection projection = orderPaymentListProjection(
                OrderStatus.PAID,
                OrderStatus.PAID,
                PAID_AT
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("approvedAt"),
                Sort.Order.asc("orderProductId")
            ));

            given(orderPaymentRepository.searchOrderPayments(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderPaymentListResponse> response = orderService.getOrderPayments(BUYER_ID, request);

            // then
            assertThat(response.getNumber()).isZero();
            assertThat(response.getSize()).isEqualTo(20);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.hasNext()).isFalse();

            OrderPaymentListResponse payment = response.getContent().getFirst();
            assertThat(payment.orderId()).isEqualTo(ORDER_ID);
            assertThat(payment.orderProductId()).isEqualTo(ORDER_PRODUCT_ID);
            assertThat(payment.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(payment.paymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.isRefund()).isFalse();
            assertThat(payment.productType()).isEqualTo(PRODUCT_TYPE_PROMPT);
            assertThat(payment.title()).isEqualTo(PRODUCT_TITLE_1);
            assertThat(payment.amount()).isEqualTo(PRODUCT_AMOUNT_1);
            assertThat(payment.paidAt()).isEqualTo(PAID_AT);

            then(orderPaymentRepository).should().searchOrderPayments(BUYER_ID, pageable);
        }

        @Test
        @DisplayName("주문 또는 주문상품이 환불 상태이면 isRefund가 true이다")
        void getMyOrderPayments_refundedStatus_isRefund() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderPaymentListProjection projection = orderPaymentListProjection(
                OrderStatus.PAID,
                OrderStatus.REFUNDED,
                PAID_AT
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("approvedAt"),
                Sort.Order.asc("orderProductId")
            ));

            given(orderPaymentRepository.searchOrderPayments(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderPaymentListResponse> response = orderService.getOrderPayments(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().paymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(response.getContent().getFirst().isRefund()).isTrue();
        }

        @Test
        @DisplayName("order.paidAt이 없으면 order_payment.approvedAt을 paidAt으로 반환한다")
        void getMyOrderPayments_paidAtFallback_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderPaymentListProjection projection = orderPaymentListProjection(
                OrderStatus.PAID,
                OrderStatus.PAID,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("approvedAt"),
                Sort.Order.asc("orderProductId")
            ));

            given(orderPaymentRepository.searchOrderPayments(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderPaymentListResponse> response = orderService.getOrderPayments(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().paidAt()).isEqualTo(APPROVED_OFFSET_AT.toLocalDateTime());
        }
    }
}
