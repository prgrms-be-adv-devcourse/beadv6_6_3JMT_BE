package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(orderProduct.getCreatedAt()).isNotNull();
			assertThat(orderProduct.getUpdatedAt()).isNotNull();
			assertThat(orderProduct.getCanceledAt()).isNull();
			assertThat(orderProduct.getRefundedAt()).isNull();
			assertThat(orderProduct.isDownloaded()).isFalse();
			assertThat(orderProduct.getVersion()).isZero();
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.PAID);
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
	@DisplayName("주문상품 부분 환불 상태 전이")
	class PartialRefund {

		@Test
		@DisplayName("PAID 주문상품은 REFUND_REQUESTED 상태로 변경할 수 있다")
		void requestRefund_paidOrderProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			orderProduct.requestRefund();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(orderProduct.isRefundable()).isFalse();
		}

		@Test
		@DisplayName("PAID가 아닌 주문상품은 부분 환불을 요청할 수 없다")
		void requestRefund_notPaidOrderProduct_throwsException() {
			OrderProduct orderProduct = createOrderProduct1();

			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("REFUND_REQUESTED 주문상품은 REFUNDED 상태로 완료할 수 있다")
		void completeRefund_requestedOrderProduct_success() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			orderProduct.completeRefund(REFUNDED_AT);

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(orderProduct.getRefundedAt()).isEqualTo(REFUNDED_AT);
		}

		@Test
		@DisplayName("REFUND_REQUESTED가 아닌 주문상품은 부분 환불을 완료할 수 없다")
		void completeRefund_notRequestedOrderProduct_throwsException() {
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			assertThatThrownBy(() -> orderProduct.completeRefund(REFUNDED_AT))
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderProduct.getCanceledAt()).isNull();
		}
	}

	@Nested
	@DisplayName("주문상품 환불")
	class Refund {

		@Test
		@DisplayName("PAID 상태의 주문상품은 REFUNDED 상태로 변경할 수 있다")
		void refund_paidOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.refund();

			// then
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
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
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.PAID);
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
