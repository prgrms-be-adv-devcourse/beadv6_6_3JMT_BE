package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.response.ProductDownloadResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ConfirmDownloadCommandHandlerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @InjectMocks
    private ConfirmDownloadCommandHandler confirmDownloadCommandHandler;

    @Nested
    @DisplayName("주문상품 다운로드 확정")
    class ConfirmDownload {

        @Test
        @DisplayName("결제 완료된 본인 주문상품이면 다운로드 처리하고 환불 불가로 응답한다")
        void confirmDownload_paidOwnerOrderProduct_success() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();

            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willReturn(new ProductContent(orderProduct.getProductId(), "content"));

            // when
            ProductDownloadResponse response = confirmDownloadCommandHandler.confirmDownload(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.downloaded()).isTrue();
            assertThat(orderProduct.isDownloaded()).isTrue();

            then(orderRepository).should().findByIdWithOrderProductsForUpdate(order.getId());
            then(orderRepository).should(never()).findByIdWithOrderProducts(any());
            then(productClient).should().getProductContent(orderProduct.getProductId());
        }

		@Test
		@DisplayName("부분 환불 주문의 남은 결제 상품은 잠금 후 다운로드 처리한다")
		void confirmDownload_partialRefundedRemainingPaidProduct_success() {
			Order order = createPaidOrderWithProducts();
			OrderProduct refundedProduct = order.getOrderProducts().getFirst();
			OrderProduct remainingProduct = order.getOrderProducts().get(1);
			order.refundOrderProduct(refundedProduct.getId(), refundedProduct.getProductAmount(), REFUNDED_AT);
			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
			given(productClient.getProductContent(remainingProduct.getProductId()))
				.willReturn(new ProductContent(remainingProduct.getProductId(), "content"));

			ProductDownloadResponse response = confirmDownloadCommandHandler.confirmDownload(
				BUYER_ID,
				order.getId(),
				remainingProduct.getId()
			);

			assertThat(response.downloaded()).isTrue();
			assertThat(remainingProduct.isDownloaded()).isTrue();
			then(orderRepository).should().findByIdWithOrderProductsForUpdate(order.getId());
			then(orderRepository).should(never()).findByIdWithOrderProducts(any());
		}

		@Test
		@DisplayName("부분 환불 주문의 환불 상품은 잠금 후 다운로드를 거부한다")
		void confirmDownload_partialRefundedRefundedProduct_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderProduct refundedProduct = order.getOrderProducts().getFirst();
			order.refundOrderProduct(refundedProduct.getId(), refundedProduct.getProductAmount(), REFUNDED_AT);
			given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));

			assertThatThrownBy(() -> confirmDownloadCommandHandler.confirmDownload(
				BUYER_ID,
				order.getId(),
				refundedProduct.getId()
			))
				.isInstanceOf(OrderException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_CONTENT_ACCESS_DENIED);

			then(productClient).shouldHaveNoInteractions();
			then(orderRepository).should().findByIdWithOrderProductsForUpdate(order.getId());
			then(orderRepository).should(never()).findByIdWithOrderProducts(any());
		}

        @Test
        @DisplayName("상품 콘텐츠 조회가 실패하면 다운로드 상태를 변경하지 않고 예외를 전파한다")
        void confirmDownload_productContentFailureDoesNotMarkDownloaded() {
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willThrow(new com.prompthub.exception.BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> confirmDownloadCommandHandler.confirmDownload(BUYER_ID, order.getId(), orderProduct.getId()))
                .isInstanceOf(com.prompthub.exception.BusinessException.class)
                .satisfies(exception -> assertThat(
                    ((com.prompthub.exception.BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

            assertThat(orderProduct.isDownloaded()).isFalse();
        }

        @Test
        @DisplayName("이미 다운로드된 주문상품도 정상 성공한다")
        void confirmDownload_alreadyDownloaded_success() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            orderProduct.markDownloaded();

            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));
            given(productClient.getProductContent(orderProduct.getProductId()))
                .willReturn(new ProductContent(orderProduct.getProductId(), "content"));

            // when
            ProductDownloadResponse response = confirmDownloadCommandHandler.confirmDownload(BUYER_ID, order.getId(), orderProduct.getId());

            // then
            assertThat(response.downloaded()).isTrue();
            assertThat(orderProduct.isDownloaded()).isTrue();
        }

        @Test
        @DisplayName("주문 소유자가 아니면 다운로드 확정에 실패한다")
        void confirmDownload_notOwner_throwsException() {
            // given
            Order order = createPaidOrderWithProducts();
            UUID otherBuyerId = UUID.fromString("00000000-0000-0000-0000-000000000777");

            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> confirmDownloadCommandHandler.confirmDownload(otherBuyerId, order.getId(), order.getOrderProducts().getFirst().getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );

            then(productClient).should(never()).getProductContent(any());
        }

        @Test
        @DisplayName("PAID 상태가 아닌 주문은 다운로드 처리할 수 없다")
        void confirmDownload_notPaidOrder_throwsException() {
            // given
            Order order = createPendingOrderWithProducts();

            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> confirmDownloadCommandHandler.confirmDownload(BUYER_ID, order.getId(), order.getOrderProducts().getFirst().getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).should(never()).getProductContent(any());
        }

        @Test
        @DisplayName("PAID 상태가 아닌 주문상품은 다운로드 처리할 수 없다")
        void confirmDownload_notPaidOrderProduct_throwsException() {
            // given
            Order order = createPaidOrderWithProducts();
            OrderProduct orderProduct = order.getOrderProducts().getFirst();
            ReflectionTestUtils.setField(orderProduct, "orderStatus", OrderProductStatus.REFUNDED);

            given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
                .willReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> confirmDownloadCommandHandler.confirmDownload(BUYER_ID, order.getId(), orderProduct.getId()))
                .isInstanceOf(OrderException.class)
                .satisfies(exception ->
                    assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_CONTENT_ACCESS_DENIED)
                );

            then(productClient).should(never()).getProductContent(any());
        }
    }


}
