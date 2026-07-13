package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefundWithAllProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRefundTest {

	private static final UUID REFUND_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 13, 12, 0);

	@Test
	@DisplayName("다건 환불을 생성하면 총액과 65초 후 최초 조회 시점을 계산한다")
	void create_multiProducts_requested() {
		Order order = createPaidOrderWithProducts();
		OrderRefund refund = OrderRefund.create(REFUND_ID, order, PAYMENT_ID, BUYER_ID, "  고객 변심  ", REQUESTED_AT);

		order.getOrderProducts().forEach(refund::addProduct);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
		assertThat(refund.getReason()).isEqualTo("고객 변심");
		assertThat(refund.getRefundProducts()).hasSize(2);
		assertThat(refund.getTotalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusSeconds(65));
		assertThat(refund.getReconciliationAttempt()).isZero();
	}

	@Test
	@DisplayName("공백으로만 구성된 환불 사유는 null로 정규화한다")
	void create_blankReason_normalizesToNull() {
		Order order = createPaidOrderWithProducts();

		OrderRefund refund = OrderRefund.create(REFUND_ID, order, PAYMENT_ID, BUYER_ID, "   ", REQUESTED_AT);

		assertThat(refund.getReason()).isNull();
	}

	@Test
	@DisplayName("환불 성공은 요청과 모든 상품을 완료 상태로 변경한다")
	void complete_requested_completesAllProducts() {
		OrderRefund refund = refundWithTwoProducts();
		LocalDateTime completedAt = REQUESTED_AT.plusSeconds(30);

		refund.complete(completedAt);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
		assertThat(refund.getCompletedAt()).isEqualTo(completedAt);
		assertThat(refund.getNextCheckAt()).isNull();
		assertThat(refund.getRefundProducts())
			.extracting(item -> item.getOrderProduct().getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUNDED);
	}

	@Test
	@DisplayName("환불 실패는 요청과 모든 상품을 실패 상태로 변경한다")
	void fail_requested_failsAllProducts() {
		OrderRefund refund = refundWithTwoProducts();
		LocalDateTime failedAt = REQUESTED_AT.plusSeconds(40);

		refund.fail("PG_REJECTED", "환불 거절", failedAt);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
		assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
		assertThat(refund.getFailureReason()).isEqualTo("환불 거절");
		assertThat(refund.getRefundProducts())
			.extracting(item -> item.getOrderProduct().getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUND_FAILED);
	}

	@Test
	@DisplayName("TIMEOUT 이후 늦은 성공 결과를 완료 상태로 반영한다")
	void complete_timeout_acceptsLateResult() {
		OrderRefund refund = refundWithTwoProducts();
		refund.timeout(REQUESTED_AT.plusMinutes(18));

		refund.complete(REQUESTED_AT.plusMinutes(19));

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
		assertThat(refund.getRefundProducts())
			.extracting(item -> item.getOrderProduct().getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUNDED);
	}

	@Test
	@DisplayName("환불 결과의 결제·주문·금액이 요청과 다르면 거부한다")
	void validateResult_mismatchedResult_throwsException() {
		OrderRefund refund = refundWithTwoProducts();

		assertThatThrownBy(() -> refund.validateResult(PAYMENT_ID, UUID.randomUUID(), TOTAL_AMOUNT))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_EVENT_MISMATCH);
	}

	private OrderRefund refundWithTwoProducts() {
		Order order = createPaidOrderWithProducts();
		return createRequestedRefundWithAllProducts(order, REFUND_ID, REQUESTED_AT);
	}
}
