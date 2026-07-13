package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderProductTest {

	@Nested
	@DisplayName("주문상품 생성")
	class Create {

		@Test
		@DisplayName("주문상품을 생성하면 PENDING 상태가 된다")
		void create_success() {
			// when
			OrderProduct orderProduct = createOrderProduct1();

			// then
			assertThat(orderProduct.getId()).isNotNull();
			assertThat(orderProduct.getOrder()).isNull();
			assertThat(orderProduct.getProductId()).isEqualTo(PRODUCT_ID_1);
			assertThat(orderProduct.getSellerId()).isEqualTo(SELLER_ID_1);
			assertThat(orderProduct.getProductTitle()).isEqualTo(PRODUCT_TITLE_1);
			assertThat(orderProduct.getProductType()).isEqualTo(PRODUCT_TYPE_PROMPT);
			assertThat(orderProduct.getProductAmount()).isEqualTo(PRODUCT_AMOUNT_1);
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.PENDING);
			assertThat(orderProduct.getCreatedAt()).isNotNull();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
			assertThat(orderProduct.getCanceledAt()).isNull();
			assertThat(orderProduct.getRefundedAt()).isNull();
			assertThat(orderProduct.isDownloaded()).isFalse();
		}
	}

	@Nested
	@DisplayName("주문상품 결제 완료 처리")
	class MarkPaid {

		@Test
		@DisplayName("PENDING 상태의 주문상품은 PAID 상태로 변경할 수 있다")
		void markPaid_pendingOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when
			orderProduct.markPaid();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.PAID);
			assertThat(orderProduct.isPaid()).isTrue();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
		}

		@Test
		@DisplayName("PENDING/FAILED 상태가 아닌 주문상품은 PAID 상태로 변경할 수 없다")
		void markPaid_notPendingOrFailedOrderProduct_throwsException() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.cancel(); // Now CANCELED

			// when & then
			assertThatThrownBy(orderProduct::markPaid)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문상품 결제 실패 처리")
	class MarkFailed {

		@Test
		@DisplayName("PENDING 상태의 주문상품은 FAILED 상태로 변경할 수 있다")
		void markFailed_pendingOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when
			orderProduct.markFailed();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.FAILED);
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
		}

		@Test
		@DisplayName("PENDING 상태가 아닌 주문상품은 FAILED 상태로 변경할 수 없다")
		void markFailed_notPendingOrderProduct_throwsException() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when & then
			assertThatThrownBy(orderProduct::markFailed)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문상품 취소")
	class Cancel {

		@Test
		@DisplayName("PAID 상태의 주문상품은 CANCELED 상태로 변경할 수 있다")
		void cancel_paidOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.cancel();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.CANCELED);
			assertThat(orderProduct.getCanceledAt()).isNotNull();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
		}

		@Test
		@DisplayName("PAID 상태가 아닌 주문상품은 취소할 수 없다")
		void cancel_notPaidOrderProduct_throwsException() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when & then
			assertThatThrownBy(orderProduct::cancel)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문상품 결제 대기 만료")
	class ExpirePending {

		@Test
		@DisplayName("PENDING 상태의 주문상품은 결제 대기 만료로 CANCELED 상태가 된다")
		void expirePending_pendingOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when
			orderProduct.expirePending(CANCELED_AT);

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.CANCELED);
			assertThat(orderProduct.getCanceledAt()).isEqualTo(CANCELED_AT);
		}

		@Test
		@DisplayName("PENDING 상태가 아닌 주문상품은 결제 대기 만료 처리해도 변경되지 않는다")
		void expirePending_notPendingOrderProduct_doNothing() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.expirePending(CANCELED_AT);

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.PAID);
			assertThat(orderProduct.getCanceledAt()).isNull();
		}
	}

	@Nested
	@DisplayName("주문상품 환불")
	class Refund {

		@Test
		@DisplayName("결제 완료된 미다운로드 유료 상품은 환불 요청 상태로 변경된다")
		void requestRefund_paidNotDownloadedPaidProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			orderProduct.requestRefund();

			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUND_REQUESTED);
		}

		@Test
		@DisplayName("다운로드한 상품은 환불을 요청할 수 없다")
		void requestRefund_downloadedProduct_throwsException() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.markDownloaded();

			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("무료 상품은 환불을 요청할 수 없다")
		void requestRefund_freeProduct_throwsException() {
			OrderProduct orderProduct = OrderProduct.create(
				PRODUCT_ID_1,
				SELLER_ID_1,
				PRODUCT_TITLE_1,
				PRODUCT_TYPE_PROMPT,
				null,
				0
			);
			orderProduct.markPaid();

			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("환불 요청 상품은 환불 완료 상태와 환불 시각을 기록한다")
		void completeRefund_requestedProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			orderProduct.completeRefund(REFUNDED_AT);

			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUNDED);
			assertThat(orderProduct.getRefundedAt()).isEqualTo(REFUNDED_AT);
		}

		@Test
		@DisplayName("환불 요청 실패 시 주문상품을 환불 실패 상태로 변경한다")
		void failRefund_requestedProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			orderProduct.failRefund();

			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUND_FAILED);
		}

		@Test
		@DisplayName("환불 결과를 확정할 수 없으면 주문상품을 타임아웃 상태로 변경한다")
		void markRefundTimeout_requestedProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			orderProduct.markRefundTimeout();

			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUND_TIMEOUT);
		}

		@Test
		@DisplayName("타임아웃 이후 실패가 확인되면 환불 실패 상태로 확정한다")
		void failRefund_timeoutProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();
			orderProduct.markRefundTimeout();

			orderProduct.failRefund();

			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUND_FAILED);
		}

		@Test
		@DisplayName("PAID 상태의 주문상품은 REFUNDED 상태로 변경할 수 있다")
		void refund_paidOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.refund();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.REFUNDED);
			assertThat(orderProduct.getRefundedAt()).isNotNull();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
		}

		@Test
		@DisplayName("PAID 상태가 아닌 주문상품은 환불할 수 없다")
		void refund_notPaidOrderProduct_throwsException() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when & then
			assertThatThrownBy(orderProduct::refund)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문상품 다운로드 처리")
	class MarkDownloaded {

		@Test
		@DisplayName("PAID 상태의 주문상품은 다운로드 처리할 수 있다")
		void markDownloaded_paidOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.markDownloaded();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderProductStatus.PAID);
			assertThat(orderProduct.isDownloaded()).isTrue();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
		}

		@Test
		@DisplayName("이미 다운로드된 주문상품은 다시 다운로드 처리해도 정상 성공한다")
		void markDownloaded_alreadyDownloaded_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.markDownloaded();

			// when
			orderProduct.markDownloaded();

			// then
			assertThat(orderProduct.isDownloaded()).isTrue();
		}

		@Test
		@DisplayName("PAID 상태이고 다운로드하지 않은 주문상품은 환불 가능하다")
		void isRefundable_paidAndNotDownloaded_returnsTrue() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when & then
			assertThat(orderProduct.isRefundable()).isTrue();
		}

		@Test
		@DisplayName("다운로드한 주문상품은 환불 가능하지 않다")
		void isRefundable_downloaded_returnsFalse() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.markDownloaded();

			// when & then
			assertThat(orderProduct.isRefundable()).isFalse();
		}

		@Test
		@DisplayName("PAID 상태가 아닌 주문상품은 환불 가능하지 않다")
		void isRefundable_notPaid_returnsFalse() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when & then
			assertThat(orderProduct.isRefundable()).isFalse();
		}
	}
}
