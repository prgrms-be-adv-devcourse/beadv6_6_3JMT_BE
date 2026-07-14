package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderFixture.*;
import static com.prompthub.order.global.exception.ErrorCode.INVALID_ORDER_STATUS_TRANSITION;
import static com.prompthub.order.global.exception.ErrorCode.ORDER_PRODUCT_ALREADY_DOWNLOADED;
import static com.prompthub.order.global.exception.ErrorCode.ORDER_PRODUCT_REFUND_NOT_ALLOWED;
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PENDING);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.FAILED);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.CANCELED);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.CANCELED);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderProduct.getCanceledAt()).isNull();
		}
	}

	@Nested
	@DisplayName("주문상품 환불")
	class Refund {

		@Test
		@DisplayName("PAID 주문상품은 환불 요청 중 콘텐츠 접근이 잠기고 실패 복구 후 다시 접근할 수 있다")
		void requestRefund_restorePaidAfterFailure_changesAccess() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when
			orderProduct.requestRefund();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(orderProduct.canAccessContent()).isFalse();

			// when
			orderProduct.restorePaidAfterRefundFailure();

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderProduct.canAccessContent()).isTrue();
		}

		@Test
		@DisplayName("환불 요청된 주문상품은 환불 완료 처리할 수 있다")
		void completeRefund_requestedOrderProduct_success() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			// when
			orderProduct.completeRefund(REFUNDED_AT);

			// then
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(orderProduct.getRefundedAt()).isEqualTo(REFUNDED_AT);
			assertThat(orderProduct.canAccessContent()).isFalse();
		}

		@Test
		@DisplayName("다운로드한 주문상품의 환불 요청은 O019 예외가 발생한다")
		void requestRefund_downloadedOrderProduct_throwsAlreadyDownloaded() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.markDownloaded();

			// when & then
			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ORDER_PRODUCT_ALREADY_DOWNLOADED));
		}

		@Test
		@DisplayName("PAID가 아닌 주문상품의 환불 요청은 O018 예외가 발생한다")
		void requestRefund_notPaidOrderProduct_throwsNotAllowed() {
			// given
			OrderProduct orderProduct = createOrderProduct1();

			// when & then
			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ORDER_PRODUCT_REFUND_NOT_ALLOWED));
		}

		@Test
		@DisplayName("가격이 0인 주문상품의 환불 요청은 O018 예외가 발생한다")
		void requestRefund_freeOrderProduct_throwsNotAllowed() {
			// given
			OrderProduct orderProduct = OrderProduct.create(
				PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, PRODUCT_MODEL, 0
			);
			orderProduct.markPaid();

			// when & then
			assertThatThrownBy(orderProduct::requestRefund)
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ORDER_PRODUCT_REFUND_NOT_ALLOWED));
		}

		@Test
		@DisplayName("환불 요청 상태가 아니면 완료 또는 실패 복구할 수 없다")
		void completeOrRestore_notRequested_throwsInvalidTransition() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when & then
			assertThatThrownBy(() -> orderProduct.completeRefund(REFUNDED_AT))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(INVALID_ORDER_STATUS_TRANSITION));
			assertThatThrownBy(orderProduct::restorePaidAfterRefundFailure)
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(INVALID_ORDER_STATUS_TRANSITION));
		}

		@Test
		@DisplayName("환불 완료 시각이 null이면 요청 상태를 변경하지 않고 V001 예외가 발생한다")
		void completeRefund_nullRefundedAt_keepsRequestedState() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();
			orderProduct.requestRefund();

			// when & then
			assertThatThrownBy(() -> orderProduct.completeRefund(null))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(com.prompthub.order.global.exception.ErrorCode.INVALID_INPUT_VALUE));
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(orderProduct.getRefundedAt()).isNull();
		}

		@Test
		@DisplayName("레거시 환불 완료 시각이 null이면 PAID 상태를 변경하지 않고 V001 예외가 발생한다")
		void refund_nullRefundedAt_keepsPaidState() {
			// given
			OrderProduct orderProduct = createOrderProduct1();
			orderProduct.markPaid();

			// when & then
			assertThatThrownBy(() -> orderProduct.refund(null))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(com.prompthub.order.global.exception.ErrorCode.INVALID_INPUT_VALUE));
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderProduct.getRefundedAt()).isNull();
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.REFUNDED);
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
			assertThat(orderProduct.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
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
