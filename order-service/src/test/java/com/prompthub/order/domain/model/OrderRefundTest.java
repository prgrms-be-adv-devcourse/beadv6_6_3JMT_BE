package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderRefundFixture.COMPLETED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.FAILED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.paidProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRefundTest {

    @Test
    @DisplayName("두 주문상품으로 환불을 요청하면 상품별 금액과 합계를 저장하고 2분 뒤 확인을 예약한다")
    void request_twoProducts_createsRequestedAggregate() {
        OrderRefund refund = requestTwoProducts();

        assertThat(refund.getId()).isNotNull();
        assertThat(refund.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(refund.getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(refund.getTotalRefundAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(refund.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(2));
        assertThat(refund.getReconciliationAttempt()).isZero();
        assertThat(refund.isManualReviewRequired()).isFalse();
        assertThat(refund.getProducts())
            .extracting(OrderRefundProduct::getOrderProductId, OrderRefundProduct::getRefundAmount)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_1),
                org.assertj.core.groups.Tuple.tuple(ORDER_PRODUCT_ID_2, PRODUCT_AMOUNT_2)
            );
        assertThat(refund.productIds()).containsExactlyInAnyOrder(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2);
        assertThat(refund.hasExactProducts(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2))).isTrue();
        assertThat(refund.overlaps(Set.of(ORDER_PRODUCT_ID_2, UUID.randomUUID()))).isTrue();
    }

    @Test
    @DisplayName("환불 금액 합계가 int 범위를 넘으면 요청을 거부한다")
    void request_overflow_throwsArithmeticException() {
        assertThatThrownBy(() -> OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID,
            List.of(
                paidProduct(ORDER_PRODUCT_ID_1, Integer.MAX_VALUE),
                paidProduct(ORDER_PRODUCT_ID_2, 1)
            ), REQUESTED_AT
        )).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("같은 주문상품 ID가 중복되면 환불 요청을 거부한다")
    void request_duplicateProductId_throwsException() {
        assertThatThrownBy(() -> OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID,
            List.of(
                paidProduct(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_1),
                paidProduct(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_2)
            ), REQUESTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문상품 ID가 null이면 환불 요청을 거부하고 상품 상태를 유지한다")
    void request_nullProductId_rejectsWithoutMutatingProduct() {
        OrderProduct product = paidProduct(null, PRODUCT_AMOUNT_1);

        assertThatThrownBy(() -> OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID, List.of(product), REQUESTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("주문상품 금액이 0이면 환불 요청을 거부하고 상품 상태를 유지한다")
    void request_zeroProductAmount_rejectsWithoutMutatingProduct() {
        OrderProduct product = paidProduct(ORDER_PRODUCT_ID_1, 0);

        assertThatThrownBy(() -> OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID, List.of(product), REQUESTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("주문상품 금액이 음수이면 환불 요청을 거부하고 상품 상태를 유지한다")
    void request_negativeProductAmount_rejectsWithoutMutatingProduct() {
        OrderProduct product = paidProduct(ORDER_PRODUCT_ID_1, -1);

        assertThatThrownBy(() -> OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID, List.of(product), REQUESTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("환불 상품 목록은 외부에서 변경할 수 없고 합계와 행 개수를 유지한다")
    void products_externalMutation_isRejected() {
        OrderRefund refund = requestTwoProducts();

        assertThatThrownBy(() -> refund.getProducts().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(refund.getProducts()).hasSize(2);
        assertThat(refund.getTotalRefundAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
    }

    @Test
    @DisplayName("처리 중, 임대, 재예약, 알 수 없음, 수동 검토 상태를 기록한다")
    void reconciliationTransitions_updateSchedulingFields() {
        OrderRefund refund = requestTwoProducts();

        refund.markProcessing(REQUESTED_AT.plusMinutes(5));
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.PROCESSING);
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(5));

        refund.leaseUntil(REQUESTED_AT.plusMinutes(6));
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(6));

        refund.scheduleNext(3, REQUESTED_AT.plusMinutes(20));
        assertThat(refund.getReconciliationAttempt()).isEqualTo(3);
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(20));

        refund.markUnknown(REQUESTED_AT.plusMinutes(30));
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.UNKNOWN);
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(30));

        refund.requireManualReview();
        assertThat(refund.isManualReviewRequired()).isTrue();
        assertThat(refund.getNextCheckAt()).isNull();
    }

    @Test
    @DisplayName("환불을 완료하면 완료 시각을 저장하고 다음 확인을 해제한다")
    void complete_requested_completes() {
        OrderRefund refund = requestTwoProducts();

        refund.complete(COMPLETED_AT);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(refund.getNextCheckAt()).isNull();
    }

    @Test
    @DisplayName("완료 결과는 같은 시각의 재전달만 멱등 처리하고 다른 시각은 거부한다")
    void complete_terminalReplay_requiresIdenticalPayload() {
        OrderRefund refund = requestTwoProducts();
        refund.complete(COMPLETED_AT);

        refund.complete(COMPLETED_AT);
        assertThat(refund.getCompletedAt()).isEqualTo(COMPLETED_AT);

        assertThatThrownBy(() -> refund.complete(COMPLETED_AT.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(refund.getCompletedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    @DisplayName("환불 실패를 기록하면 실패 정보와 재시도 가능 여부를 저장한다")
    void fail_requested_fails() {
        OrderRefund refund = requestTwoProducts();

        refund.fail("PG_REJECTED", "승인 거절", false, FAILED_AT);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
        assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
        assertThat(refund.getFailureReason()).isEqualTo("승인 거절");
        assertThat(refund.isRetryable()).isFalse();
        assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
        assertThat(refund.getNextCheckAt()).isNull();
    }

    @Test
    @DisplayName("실패 결과는 동일한 재전달만 멱등 처리하고 각 필드 충돌은 거부한다")
    void fail_terminalReplay_requiresIdenticalPayload() {
        OrderRefund refund = requestTwoProducts();
        refund.fail("PG_REJECTED", "승인 거절", true, FAILED_AT);

        refund.fail("PG_REJECTED", "승인 거절", true, FAILED_AT);
        assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");

        assertThatThrownBy(() -> refund.fail("OTHER", "승인 거절", true, FAILED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refund.fail("PG_REJECTED", "다른 사유", true, FAILED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refund.fail("PG_REJECTED", "승인 거절", false, FAILED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refund.fail(
            "PG_REJECTED", "승인 거절", true, FAILED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
        assertThat(refund.getFailureReason()).isEqualTo("승인 거절");
        assertThat(refund.isRetryable()).isTrue();
        assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
    }

    @Test
    @DisplayName("완료와 실패 결과는 반대 종결 상태로 덮어쓸 수 없다")
    void terminalState_oppositeOverwrite_throwsException() {
        OrderRefund completed = requestTwoProducts();
        completed.complete(COMPLETED_AT);
        OrderRefund failed = requestTwoProducts();
        failed.fail("PG_REJECTED", "승인 거절", true, FAILED_AT);

        assertThatThrownBy(() -> completed.fail("LATE", "늦은 실패", true, FAILED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.complete(COMPLETED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completed.markProcessing(REQUESTED_AT.plusMinutes(3)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("필수 시각이 없으면 예외 후에도 환불 상태와 스케줄을 변경하지 않는다")
    void transition_nullTimestamp_preservesState() {
        OrderRefund refund = requestTwoProducts();

        assertThatThrownBy(() -> refund.markProcessing(null))
            .isInstanceOf(NullPointerException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(2));

        assertThatThrownBy(() -> refund.complete(null))
            .isInstanceOf(NullPointerException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(refund.getCompletedAt()).isNull();

        assertThatThrownBy(() -> refund.fail("ERROR", "reason", true, null))
            .isInstanceOf(NullPointerException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(refund.getFailureCode()).isNull();
        assertThat(refund.getFailedAt()).isNull();

        assertThatThrownBy(() -> refund.markUnknown(null))
            .isInstanceOf(NullPointerException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);

        assertThatThrownBy(() -> refund.scheduleNext(3, null))
            .isInstanceOf(NullPointerException.class);
        assertThat(refund.getReconciliationAttempt()).isZero();
    }

    @Test
    @DisplayName("결과 식별자와 금액이 요청과 다르면 이벤트 불일치 예외를 던진다")
    void requireMatches_mismatch_throwsOrderException() {
        OrderRefund refund = requestTwoProducts();

        assertThatThrownBy(() -> refund.requireMatches(PAYMENT_ID, ORDER_ID, PRODUCT_AMOUNT_1))
            .isInstanceOf(OrderException.class);
    }

    private OrderRefund requestTwoProducts() {
        return OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID,
            List.of(
                paidProduct(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_1),
                paidProduct(ORDER_PRODUCT_ID_2, PRODUCT_AMOUNT_2)
            ), REQUESTED_AT
        );
    }
}
