package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRefundTest {

	private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 15, 12, 0);
	private static final LocalDateTime NEXT_CHECK_AT = REQUESTED_AT.plusMinutes(10);
	private static final LocalDateTime FAILED_AT = REQUESTED_AT.plusMinutes(1);

	@Test
	@DisplayName("단건 환불 요청은 집계와 동일한 금액의 상세 한 건을 생성한다")
	void request_createsSingleProductDetail() {
		OrderProduct orderProduct = createPaidOrderWithProducts().getOrderProducts().getFirst();

		OrderRefund refund = request(orderProduct);

		assertThat(refund.getId()).isNotNull();
		assertThat(refund.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(refund.getBuyerId()).isEqualTo(BUYER_ID);
		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
		assertThat(refund.getTotalRefundAmount()).isEqualTo(PRODUCT_AMOUNT_1);
		assertThat(refund.getCheckCount()).isZero();
		assertThat(refund.getNextCheckAt()).isEqualTo(NEXT_CHECK_AT);
		assertThat(refund.getRequestedAt()).isEqualTo(REQUESTED_AT);
		assertThat(refund.getVersion()).isZero();
		assertThat(refund.getProduct().getOrderRefund()).isSameAs(refund);
		assertThat(refund.getProduct().getOrderProductId()).isEqualTo(orderProduct.getId());
		assertThat(refund.getProduct().getRefundAmount()).isEqualTo(refund.getTotalRefundAmount());
		assertThat(refund.getProduct().getCreatedAt()).isEqualTo(REQUESTED_AT);
	}

	@Test
	@DisplayName("환불 금액이 양수가 아니면 요청을 생성할 수 없다")
	void request_nonPositiveAmount_throwsException() {
		OrderProduct orderProduct = createPaidOrderWithProducts().getOrderProducts().getFirst();

		assertThatThrownBy(() -> OrderRefund.request(
			ORDER_ID,
			PAYMENT_ID,
			BUYER_ID,
			orderProduct.getId(),
			0,
			REQUESTED_AT,
			NEXT_CHECK_AT
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("REQUESTED 환불을 완료하면 조회 일정을 제거한다")
	void complete_requestedRefund_success() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());

		refund.complete(REFUNDED_AT);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
		assertThat(refund.getCompletedAt()).isEqualTo(REFUNDED_AT);
		assertThat(refund.getNextCheckAt()).isNull();
	}

	@Test
	@DisplayName("REQUESTED 환불 실패는 실패 정보만 기록한다")
	void fail_requestedRefund_success() {
		Order order = createPaidOrderWithProducts();
		OrderProduct orderProduct = order.getOrderProducts().getFirst();
		order.requestRefund(orderProduct.getId());
		OrderRefund refund = request(orderProduct);

		refund.fail("PAYMENT_REFUND_FAILED", "PG 환불 실패", FAILED_AT);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
		assertThat(refund.getFailureCode()).isEqualTo("PAYMENT_REFUND_FAILED");
		assertThat(refund.getFailureReason()).isEqualTo("PG 환불 실패");
		assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
		assertThat(refund.getNextCheckAt()).isNull();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(orderProduct.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
	}

	@Test
	@DisplayName("REQUESTED 환불은 조회 횟수와 다음 조회 시각을 갱신할 수 있다")
	void scheduleNextCheck_requestedRefund_success() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		LocalDateTime rescheduledAt = NEXT_CHECK_AT.plusMinutes(10);

		refund.scheduleNextCheck(rescheduledAt);

		assertThat(refund.getCheckCount()).isEqualTo(1);
		assertThat(refund.getNextCheckAt()).isEqualTo(rescheduledAt);
	}

	@Test
	@DisplayName("DLQ 환불은 늦은 성공 결과를 반영할 수 있다")
	void complete_dlqRefund_success() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		refund.moveToDlq();

		refund.complete(REFUNDED_AT);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
		assertThat(refund.getCompletedAt()).isEqualTo(REFUNDED_AT);
	}

	@Test
	@DisplayName("DLQ 환불은 늦은 실패 결과를 반영할 수 있다")
	void fail_dlqRefund_success() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		refund.moveToDlq();

		refund.fail("PAYMENT_REFUND_FAILED", null, FAILED_AT);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
		assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
	}

	@Test
	@DisplayName("종결된 환불 결과는 다른 상태로 덮어쓸 수 없다")
	void fail_completedRefund_throwsException() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		refund.complete(REFUNDED_AT);

		assertThatThrownBy(() -> refund.fail("PAYMENT_REFUND_FAILED", null, FAILED_AT))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("실패로 종결된 환불은 성공 결과로 덮어쓸 수 없다")
	void complete_failedRefund_throwsException() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		refund.fail("PAYMENT_REFUND_FAILED", null, FAILED_AT);

		assertThatThrownBy(() -> refund.complete(REFUNDED_AT))
			.isInstanceOf(IllegalStateException.class);
		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
	}

	@Test
	@DisplayName("DLQ 환불은 자동 조회 일정으로 되돌릴 수 없다")
	void scheduleNextCheck_dlqRefund_throwsException() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());
		refund.moveToDlq();

		assertThatThrownBy(() -> refund.scheduleNextCheck(NEXT_CHECK_AT.plusMinutes(10)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.DLQ);
		assertThat(refund.getNextCheckAt()).isNull();
	}

	@Test
	@DisplayName("완료 시각이 없으면 환불 요청 상태를 변경하지 않는다")
	void complete_nullCompletedAt_throwsWithoutChanges() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());

		assertThatThrownBy(() -> refund.complete(null))
			.isInstanceOf(NullPointerException.class);
		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
		assertThat(refund.getCompletedAt()).isNull();
		assertThat(refund.getNextCheckAt()).isEqualTo(NEXT_CHECK_AT);
	}

	@Test
	@DisplayName("실패 시각이 없으면 환불 요청 상태와 실패 정보를 변경하지 않는다")
	void fail_nullFailedAt_throwsWithoutChanges() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());

		assertThatThrownBy(() -> refund.fail("PAYMENT_REFUND_FAILED", "실패", null))
			.isInstanceOf(NullPointerException.class);
		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
		assertThat(refund.getFailureCode()).isNull();
		assertThat(refund.getFailureReason()).isNull();
		assertThat(refund.getNextCheckAt()).isEqualTo(NEXT_CHECK_AT);
	}

	@Test
	@DisplayName("다음 조회 시각이 없으면 조회 횟수를 변경하지 않는다")
	void scheduleNextCheck_nullNextCheckAt_throwsWithoutChanges() {
		OrderRefund refund = request(createPaidOrderWithProducts().getOrderProducts().getFirst());

		assertThatThrownBy(() -> refund.scheduleNextCheck(null))
			.isInstanceOf(NullPointerException.class);
		assertThat(refund.getCheckCount()).isZero();
		assertThat(refund.getNextCheckAt()).isEqualTo(NEXT_CHECK_AT);
	}

	private OrderRefund request(OrderProduct orderProduct) {
		return OrderRefund.request(
			ORDER_ID,
			PAYMENT_ID,
			BUYER_ID,
			orderProduct.getId(),
			orderProduct.getProductAmount(),
			REQUESTED_AT,
			NEXT_CHECK_AT
		);
	}
}
