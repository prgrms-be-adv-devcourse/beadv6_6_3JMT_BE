package com.prompthub.order.domain.model;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestJpaConfig.class)
class OrderTest {

	@Nested
	@DisplayName("주문 생성")
	class Create {

		@Test
		@DisplayName("주문을 생성하면 PENDING 상태가 된다")
		void create_success() {
			// when
			Order order = createPendingOrder();

			// then
			assertThat(order.getId()).isNotNull();
			assertThat(order.getBuyerId()).isEqualTo(BUYER_ID);
			assertThat(order.getOrderNumber()).isEqualTo(ORDER_NUMBER);
			assertThat(order.getTotalOrderAmount()).isEqualTo(TOTAL_AMOUNT);
			assertThat(order.getTotalProductCount()).isEqualTo(TOTAL_ITEM_COUNT);
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(order.getCreatedAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();
			assertThat(order.getPaidAt()).isNull();
			assertThat(order.getCanceledAt()).isNull();
			assertThat(order.getRefundedAt()).isNull();
			assertThat(order.getOrderProducts()).isEmpty();
			assertThat(order.getVersion()).isZero();
		}
	}

	@Nested
	@DisplayName("주문상품 추가")
	class AddOrderProduct {

		@Test
		@DisplayName("주문에 주문상품을 추가하면 양방향 연관관계가 설정된다")
		void addOrderProduct_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();

			// when
			order.addOrderProduct(orderProduct);

			// then
			assertThat(order.getOrderProducts()).containsExactly(orderProduct);
			assertThat(orderProduct.getOrder()).isSameAs(order);
		}
	}

	@Nested
	@DisplayName("주문상품 단건 부분 환불")
	class PartialRefund {

		@Test
		@DisplayName("PAID 주문에서 선택한 상품만 REFUND_REQUESTED로 변경한다")
		void requestRefund_paidOrder_changesOnlySelectedProduct() {
			Order order = createPaidOrderWithProducts();
			OrderProduct selected = order.getOrderProducts().getFirst();
			OrderProduct unselected = order.getOrderProducts().getLast();

			order.requestRefund(selected.getId());

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(selected.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(unselected.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		}

		@Test
		@DisplayName("선택 상품 환불 완료 후 PAID 상품이 남으면 PARTIAL_REFUNDED가 된다")
		void completeRefund_paidProductRemains_marksPartialRefunded() {
			Order order = createPaidOrderWithProducts();
			OrderProduct selected = order.getOrderProducts().getFirst();
			order.requestRefund(selected.getId());

			order.completeRefund(selected.getId(), REFUNDED_AT);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
			assertThat(order.getRefundedAt()).isNull();
			assertThat(selected.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
		}

		@Test
		@DisplayName("PARTIAL_REFUNDED 주문의 남은 PAID 상품을 순차 요청할 수 있다")
		void requestRefund_partialRefundedOrder_success() {
			Order order = createPaidOrderWithProducts();
			OrderProduct first = order.getOrderProducts().getFirst();
			OrderProduct second = order.getOrderProducts().getLast();
			order.requestRefund(first.getId());
			order.completeRefund(first.getId(), REFUNDED_AT);

			order.requestRefund(second.getId());

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(first.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(second.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		}

		@Test
		@DisplayName("모든 주문상품의 환불이 완료되면 주문이 REFUNDED가 된다")
		void completeRefund_noPaidProductRemains_marksRefunded() {
			Order order = createPaidOrderWithProducts();
			OrderProduct first = order.getOrderProducts().getFirst();
			OrderProduct second = order.getOrderProducts().getLast();
			order.requestRefund(first.getId());
			order.completeRefund(first.getId(), REFUNDED_AT.minusMinutes(1));
			order.requestRefund(second.getId());

			order.completeRefund(second.getId(), REFUNDED_AT);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderStatus.REFUNDED);
		}

		@Test
		@DisplayName("주문에 속하지 않은 상품을 요청하면 주문과 상품 상태를 변경하지 않는다")
		void requestRefund_unknownOrderProduct_throwsWithoutChanges() {
			Order order = createPaidOrderWithProducts();

			assertThatThrownBy(() -> order.requestRefund(UNKNOWN_PRODUCT_ID))
				.isInstanceOf(OrderException.class);
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderStatus.PAID);
		}

		@Test
		@DisplayName("PAID 또는 PARTIAL_REFUNDED가 아닌 주문은 부분 환불을 요청할 수 없다")
		void requestRefund_invalidOrderStatus_throwsWithoutChanges() {
			Order order = createPendingOrderWithProducts();
			OrderProduct selected = order.getOrderProducts().getFirst();

			assertThatThrownBy(() -> order.requestRefund(selected.getId()))
				.isInstanceOf(OrderException.class);
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(selected.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		}

		@Test
		@DisplayName("REFUND_REQUESTED가 아닌 주문은 부분 환불을 완료할 수 없다")
		void completeRefund_invalidOrderStatus_throwsWithoutChanges() {
			Order order = createPaidOrderWithProducts();
			OrderProduct selected = order.getOrderProducts().getFirst();

			assertThatThrownBy(() -> order.completeRefund(selected.getId(), REFUNDED_AT))
				.isInstanceOf(OrderException.class);
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(selected.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		}
	}

	@Nested
	@DisplayName("주문 결제 완료 처리")
	class MarkPaid {

		@Test
		@DisplayName("PENDING 상태의 주문은 PAID 상태로 변경할 수 있다")
		void markPaid_pendingOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);

			// when
			order.markPaid();

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(order.isPaid()).isTrue();
			assertThat(order.getPaidAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderProduct.isPaid()).isTrue();
		}

		@Test
		@DisplayName("PENDING/FAILED 상태가 아닌 주문은 PAID 상태로 변경할 수 없다")
		void markPaid_notPendingOrFailedOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.cancel(); // Now CANCELED

			// when & then
			assertThatThrownBy(order::markPaid)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문 결제 실패 처리")
	class MarkFailed {

		@Test
		@DisplayName("PENDING 상태의 주문은 FAILED 상태로 변경할 수 있다")
		void markFailed_pendingOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);

			// when
			order.markFailed();

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
			assertThat(order.getUpdatedAt()).isNotNull();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		}

		@Test
		@DisplayName("PENDING 상태가 아닌 주문은 FAILED 상태로 변경할 수 없다")
		void markFailed_notPendingOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.markPaid();

			// when & then
			assertThatThrownBy(order::markFailed)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문 취소")
	class Cancel {

		@Test
		@DisplayName("PENDING 상태의 주문은 CANCELED 상태로 변경할 수 있다")
		void cancel_pendingOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);

			// when
			order.cancel();

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(order.getCanceledAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(orderProduct.getCanceledAt()).isNotNull();
		}

		@Test
		@DisplayName("PAID 상태의 주문은 취소할 수 없다")
		void cancel_paidOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.markPaid();

			// when & then
			assertThatThrownBy(order::cancel)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문 마크 취소")
	class MarkCanceled {

		@Test
		@DisplayName("PENDING 상태의 주문은 markCanceled를 통해 CANCELED 상태로 변경할 수 있다")
		void markCanceled_pendingOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);

			// when
			order.markCanceled();

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(order.getCanceledAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(orderProduct.getCanceledAt()).isNotNull();
		}

		@Test
		@DisplayName("PAID 상태의 주문은 markCanceled를 통해 취소할 수 없다")
		void markCanceled_paidOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.markPaid();

			// when & then
			assertThatThrownBy(order::markCanceled)
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문 결제 대기 만료")
	class ExpirePending {

		@Test
		@DisplayName("PENDING 상태의 주문은 결제 대기 만료로 주문상품과 함께 CANCELED 상태가 된다")
		void expirePending_pendingOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);

			// when
			order.expirePending(CANCELED_AT);

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(order.getCanceledAt()).isEqualTo(CANCELED_AT);
			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
			assertThat(orderProduct.getCanceledAt()).isEqualTo(CANCELED_AT);
		}

		@Test
		@DisplayName("PENDING 상태가 아닌 주문은 결제 대기 만료 처리해도 변경되지 않는다")
		void expirePending_notPendingOrder_doNothing() {
			// given
			Order order = createPendingOrder();
			order.markPaid(PAID_AT);

			// when
			order.expirePending(CANCELED_AT);

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(order.getCanceledAt()).isNull();
		}

		@Test
		@DisplayName("createdAt에 제한 시간을 더한 시각이 현재 시각 이하이면 만료된 주문이다")
		void isExpired_nowAfterExpireAt_returnsTrue() {
			// given
			Order order = createPendingOrder();

			// when & then
			assertThat(order.isExpired(CREATED_AT.plusMinutes(20), 20)).isTrue();
		}

		@Test
		@DisplayName("createdAt에 제한 시간을 더한 시각보다 현재 시각이 빠르면 만료되지 않은 주문이다")
		void isExpired_nowBeforeExpireAt_returnsFalse() {
			// given
			Order order = createPendingOrder();

			// when & then
			assertThat(order.isExpired(CREATED_AT.plusMinutes(19), 20)).isFalse();
		}
	}


	@Nested
	@DisplayName("주문 환불")
	class Refund {

		@Test
		@DisplayName("PAID 상태의 주문은 REFUNDED 상태로 변경할 수 있다")
		void refund_paidOrder_success() {
			// given
			Order order = createPendingOrder();
			OrderProduct orderProduct = createOrderProduct1();
			order.addOrderProduct(orderProduct);
			order.markPaid();

			// when
			order.refund();

			// then
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(order.getRefundedAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();

			assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(orderProduct.getRefundedAt()).isNotNull();
		}

		@Test
		@DisplayName("PAID 상태가 아닌 주문은 환불할 수 없다")
		void refund_notPaidOrder_throwsException() {
			// given
			Order order = createPendingOrder();

			// when & then
			assertThatThrownBy(order::refund)
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("부분 환불 요청 중인 주문은 기존 전체 환불로 일부 상품만 변경되지 않는다")
		void refund_refundRequestedOrder_throwsWithoutProductChanges() {
			Order order = createPaidOrderWithProducts();
			OrderProduct requested = order.getOrderProducts().getLast();
			order.requestRefund(requested.getId());

			assertThatThrownBy(order::refund)
				.isInstanceOf(OrderException.class);
			assertThat(order.getOrderProducts().getFirst().getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(requested.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		}
	}

	@Nested
	@DisplayName("주문 상태 확인")
	class StatusCheck {

		@Test
		@DisplayName("PENDING 상태의 주문은 isPending이 true를 반환한다")
		void isPending_pendingOrder_true() {
			// given
			Order order = createPendingOrder();

			// when
			boolean result = order.isPending();

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("PENDING 상태가 아닌 주문은 isPending이 false를 반환한다")
		void isPending_notPendingOrder_false() {
			// given
			Order order = createPendingOrder();
			order.markPaid();

			// when
			boolean result = order.isPending();

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("PAID 상태의 주문은 isPaid가 true를 반환한다")
		void isPaid_paidOrder_true() {
			// given
			Order order = createPendingOrder();
			order.markPaid();

			// when
			boolean result = order.isPaid();

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("PAID 상태가 아닌 주문은 isPaid가 false를 반환한다")
		void isPaid_notPaidOrder_false() {
			// given
			Order order = createPendingOrder();

			// when
			boolean result = order.isPaid();

			// then
			assertThat(result).isFalse();
		}
	}
}
