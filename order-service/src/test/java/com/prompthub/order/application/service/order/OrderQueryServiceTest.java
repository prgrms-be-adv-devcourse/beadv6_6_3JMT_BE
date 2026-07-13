package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.domain.enums.PaymentStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentValidationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private ProductClient productClient;

    @Spy
    private OrderPolicyService orderPolicyService;

    @Mock
    private OrderExpirationPolicy expirationPolicy;

    @InjectMocks
    private OrderQueryService orderQueryService;

    @Nested
    @DisplayName("결제용 주문 조회")
    class GetOrderForPayment {

        @Test
        @DisplayName("주문이 존재하면 결제에 필요한 주문 정보를 반환한다")
        void getOrderForPayment_existingOrder_returnsResult() {
            Order order = createPendingOrderWithProducts();
            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            OrderForPaymentResult result = orderQueryService.getOrderForPayment(order.getId());

            assertThat(result.orderId()).isEqualTo(order.getId());
            assertThat(result.buyerId()).isEqualTo(order.getBuyerId());
            assertThat(result.totalAmount()).isEqualTo(order.getTotalOrderAmount());
            assertThat(result.createdAt()).isEqualTo(order.getCreatedAt());
        }

        @Test
        @DisplayName("주문이 존재하지 않으면 O001 예외가 발생한다")
        void getOrderForPayment_missingOrder_throwsOrderNotFound() {
            UUID orderId = UUID.randomUUID();
            given(orderRepository.findByIdWithOrderProducts(orderId))
                .willReturn(Optional.empty());

            assertThatThrownBy(() -> orderQueryService.getOrderForPayment(orderId))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );
        }
    }


    @Nested
    @DisplayName("결제 전 주문 검증")
    class ValidatePaymentReady {

        @Test
        @DisplayName("PENDING 상태이고 만료 전이며 금액이 일치하면 결제 가능 응답을 반환한다")
        void validatePaymentReady_pendingNotExpiredAndAmountMatched_success() {
            Order order = createPendingOrderWithProducts();
            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(expirationPolicy.paymentTimeoutMinutes()).willReturn(20);

            OrderPaymentValidationResponse response = orderQueryService.validatePaymentReady(
                BUYER_ID,
                order.getId(),
                TOTAL_AMOUNT,
                CREATED_AT.plusMinutes(19)
            );

            assertThat(response.payable()).isTrue();
            assertThat(response.orderId()).isEqualTo(order.getId());
            assertThat(response.buyerId()).isEqualTo(BUYER_ID);
            assertThat(response.totalAmount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(response.expiresAt()).isEqualTo(CREATED_AT.plusMinutes(20));
        }

        @Test
        @DisplayName("PENDING 상태여도 만료 시간이 지났으면 O015 예외가 발생한다")
        void validatePaymentReady_expiredOrder_throwsException() {
            Order order = createPendingOrderWithProducts();
            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(expirationPolicy.paymentTimeoutMinutes()).willReturn(20);

            assertThatThrownBy(() -> orderQueryService.validatePaymentReady(
                BUYER_ID,
                order.getId(),
                TOTAL_AMOUNT,
                CREATED_AT.plusMinutes(20)
            ))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_EXPIRED)
                );
        }

        @Test
        @DisplayName("결제 요청 금액이 주문 금액과 다르면 O014 예외가 발생한다")
        void validatePaymentReady_amountMismatch_throwsException() {
            Order order = createPendingOrderWithProducts();
            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(expirationPolicy.paymentTimeoutMinutes()).willReturn(20);

            assertThatThrownBy(() -> orderQueryService.validatePaymentReady(
                BUYER_ID,
                order.getId(),
                PRODUCT_AMOUNT_1,
                CREATED_AT.plusMinutes(19)
            ))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH)
                );
        }
    }

    @Nested
    @DisplayName("구매 상품 콘텐츠 열람")
    class GetOrderContent {

        private static final String PRODUCT_CONTENT = "구매 후 확인 가능한 프롬프트 원문";

        @Test
        @DisplayName("결제 완료된 본인 주문상품이면 콘텐츠를 반환하고 다운로드 처리하지 않는다")
        void getOrderContent_paidOwnerOrderProduct_success() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willReturn(new ProductContent(orderProduct.getProductId(), PRODUCT_CONTENT));

            // when
            OrderContentResponse response = orderQueryService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.orderId()).isEqualTo(order.getId());
            assertThat(response.orderProductId()).isEqualTo(orderProduct.getId());
            assertThat(response.orderNumber()).isEqualTo(ORDER_NUMBER);
            assertThat(response.productId()).isEqualTo(orderProduct.getProductId());
            assertThat(response.downloaded()).isFalse();
            assertThat(response.productTitle()).isEqualTo(PRODUCT_TITLE_1);
            assertThat(response.content()).isEqualTo(PRODUCT_CONTENT);
            assertThat(orderProduct.isDownloaded()).isFalse();

            then(orderRepository).should().findByIdWithOrderProducts(order.getId());
            then(productClient).should().getProductContent(orderProduct.getProductId());
        }

        @Test
        @DisplayName("상품 콘텐츠 조회가 SYS002로 실패하면 다운로드 상태를 변경하지 않고 예외를 전파한다")
        void getOrderContent_productServiceUnavailable_keepsDownloadState() {
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willThrow(new com.prompthub.exception.BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> orderQueryService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId()))
                .isInstanceOf(com.prompthub.exception.BusinessException.class)
                .satisfies(exception -> assertThat(
                    ((com.prompthub.exception.BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

            assertThat(orderProduct.isDownloaded()).isFalse();
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
            OrderContentResponse response = orderQueryService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.downloaded()).isTrue();
            assertThat(response.content()).isEqualTo(PRODUCT_CONTENT);
            assertThat(orderProduct.isDownloaded()).isTrue();

            then(productClient).should().getProductContent(orderProduct.getProductId());
        }

        @Test
        @DisplayName("주문이 없으면 O001 예외가 발생한다")
        void getOrderContent_orderNotFound_throwsException() {
            // given
            given(orderRepository.findByIdWithOrderProducts(ORDER_ID))
                .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderQueryService.getOrderContent(BUYER_ID, ORDER_ID, ORDER_PRODUCT_ID))
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
            assertThatThrownBy(() -> orderQueryService.getOrderContent(otherBuyerId, order.getId(), order.getOrderProducts().getFirst().getId()))
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
            assertThatThrownBy(() -> orderQueryService.getOrderContent(BUYER_ID, order.getId(), order.getOrderProducts().getFirst().getId()))
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
            assertThatThrownBy(() -> orderQueryService.getOrderContent(BUYER_ID, order.getId(), otherOrderProductId))
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
            ReflectionTestUtils.setField(orderProduct, "orderStatus", OrderProductStatus.REFUNDED);

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderQueryService.getOrderContent(BUYER_ID, order.getId(), orderProduct.getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("부분 환불 주문의 남은 결제 상품 콘텐츠는 조회할 수 있다")
        void getOrderContent_partiallyRefundedOrderPaidProduct_success() {
            Order order = createPaidOrderWithProducts();
            OrderProduct refundedProduct = order.getOrderProducts().getFirst();
            OrderProduct paidProduct = order.getOrderProducts().get(1);
            ReflectionTestUtils.setField(refundedProduct, "orderStatus", OrderProductStatus.REFUNDED);
            ReflectionTestUtils.setField(order, "orderStatus", OrderStatus.PARTIALLY_REFUNDED);
            given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));
            given(productClient.getProductContent(paidProduct.getProductId()))
                .willReturn(new ProductContent(paidProduct.getProductId(), PRODUCT_CONTENT));

            OrderContentResponse response = orderQueryService.getOrderContent(
                BUYER_ID, order.getId(), paidProduct.getId()
            );

            assertThat(response.content()).isEqualTo(PRODUCT_CONTENT);
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
            OrderDetailResponse response = orderQueryService.getOrderDetail(BUYER_ID, order.getId());

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
            assertThat(response.hasDownloadedProduct()).isTrue();
            assertThat(response.products()).hasSize(TOTAL_ITEM_COUNT);
            assertThat(response.products().getFirst().productId()).isEqualTo(PRODUCT_ID_1);
            assertThat(response.products().getFirst().isContentAccessible()).isTrue();
            assertThat(response.products().getFirst().isRefundable()).isFalse();
            assertThat(response.products().getFirst().downloaded()).isTrue();

            then(orderRepository).should().findByIdWithOrderProducts(order.getId());
        }

        @Test
        @DisplayName("주문이 없으면 O001 예외가 발생한다")
        void getOrderDetail_orderNotFound_throwsException() {
            // given
            given(orderRepository.findByIdWithOrderProducts(ORDER_ID))
                .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderQueryService.getOrderDetail(BUYER_ID, ORDER_ID))
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
            assertThatThrownBy(() -> orderQueryService.getOrderDetail(otherBuyerId, order.getId()))
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
            ReflectionTestUtils.setField(paidProduct, "orderStatus", OrderProductStatus.PAID);
            ReflectionTestUtils.setField(refundedProduct, "orderStatus", OrderProductStatus.REFUNDED);

            given(orderRepository.findByIdWithOrderProducts(order.getId()))
                .willReturn(Optional.of(order));

            // when
            OrderDetailResponse response = orderQueryService.getOrderDetail(BUYER_ID, order.getId());

            // then
            assertThat(response.products())
                .extracting(product -> product.isContentAccessible())
                .containsExactly(true, false);
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
                OrderProductStatus.PAID,
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
            Page<OrderListResponse> response = orderQueryService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getNumber()).isZero();
            assertThat(response.getSize()).isEqualTo(20);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.hasNext()).isFalse();

            OrderListResponse order = response.getContent().getFirst();
            assertThat(order.orderId()).isEqualTo(ORDER_ID);
            assertThat(order.orderProductId()).isEqualTo(ORDER_PRODUCT_ID);
            assertThat(order.productId()).isEqualTo(PRODUCT_ID_1);
            assertThat(order.orderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isRefundable()).isTrue();

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
            orderQueryService.getOrders(BUYER_ID, request);

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
            orderQueryService.getOrders(BUYER_ID, request);

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
                OrderProductStatus.PAID,
                false,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderQueryService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().rating()).isNull();
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
            Page<OrderListResponse> response = orderQueryService.getOrders(BUYER_ID, request);

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
                OrderProductStatus.PAID,
                PAID_AT,
                false
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("approvedAt")
            ));

            given(orderPaymentRepository.searchOrderPayments(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderPaymentListResponse> response = orderQueryService.getOrderPayments(BUYER_ID, request);

            // then
            assertThat(response.getNumber()).isZero();
            assertThat(response.getSize()).isEqualTo(20);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.hasNext()).isFalse();

            OrderPaymentListResponse payment = response.getContent().getFirst();
            assertThat(payment.orderId()).isEqualTo(ORDER_ID);
            assertThat(payment.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(payment.paymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.isRefundable()).isTrue();

            assertThat(payment.productType()).isEqualTo(PRODUCT_TYPE_PROMPT);
            assertThat(payment.title()).isEqualTo(PRODUCT_TITLE_1);
            assertThat(payment.amount()).isEqualTo(TOTAL_AMOUNT);
            assertThat(payment.paidAt()).isEqualTo(PAID_AT);

            then(orderPaymentRepository).should().searchOrderPayments(BUYER_ID, pageable);
        }

        @Test
        @DisplayName("다운로드한 상품은 환불 가능하지 않다")
        void getMyOrders_downloadedProduct_notRefundable() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.PAID,
                OrderProductStatus.PAID,
                true,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderQueryService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().isRefundable()).isFalse();
        }

        @Test
        @DisplayName("PAID가 아닌 주문은 환불 가능하지 않다")
        void getMyOrders_notPaidOrder_notRefundable() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderListProjection projection = orderListProjection(
                OrderStatus.CANCELED,
                OrderProductStatus.CANCELED,
                false,
                null
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

            given(orderRepository.searchOrderproducts(BUYER_ID, null, null, null, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderListResponse> response = orderQueryService.getOrders(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().isRefundable()).isFalse();
        }


        @Test
        @DisplayName("order.paidAt이 없으면 order_payment.approvedAt을 paidAt으로 반환한다")
        void getMyOrderPayments_paidAtFallback_success() {
            // given
            PageRequestParams request = new PageRequestParams(1, 20, null, null, null);
            OrderPaymentListProjection projection = orderPaymentListProjection(
                OrderStatus.PAID,
                OrderProductStatus.PAID,
                null,
                false
            );
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("approvedAt")
            ));

            given(orderPaymentRepository.searchOrderPayments(BUYER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(projection), pageable, 1));

            // when
            Page<OrderPaymentListResponse> response = orderQueryService.getOrderPayments(BUYER_ID, request);

            // then
            assertThat(response.getContent().getFirst().paidAt()).isEqualTo(APPROVED_AT);
        }
    }
}
