package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
			assertThat(order.getTotalItemCount()).isEqualTo(TOTAL_ITEM_COUNT);
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(order.getCreatedAt()).isNotNull();
			assertThat(order.getUpdatedAt()).isNotNull();
			assertThat(order.getPaidAt()).isNull();
			assertThat(order.getCanceledAt()).isNull();
			assertThat(order.getRefundedAt()).isNull();
			assertThat(order.getOrderProducts()).isEmpty();
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
		@DisplayName("PENDING 상태가 아닌 주문은 PAID 상태로 변경할 수 없다")
		void markPaid_notPendingOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.cancel();

			// when & then
			assertThatThrownBy(order::markPaid)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("대기 상태의 주문만 처리할 수 있습니다.");
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
			order.cancel();

			// when & then
			assertThatThrownBy(order::markFailed)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("대기 상태의 주문만 처리할 수 있습니다.");
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
		@DisplayName("PENDING 상태가 아닌 주문은 취소할 수 없다")
		void cancel_notPendingOrder_throwsException() {
			// given
			Order order = createPendingOrder();
			order.markFailed();

			// when & then
			assertThatThrownBy(order::cancel)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("대기 상태의 주문만 처리할 수 있습니다.");
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
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("결제 완료 상태의 주문만 환불할 수 있습니다.");
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
