package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderRefundFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRefundPolicyTest {

    private final OrderRefundPolicy policy = new OrderRefundPolicy();

    @Test
    void exactActiveAndCompleted_areReusable() {
        OrderRefund requested = refund(ORDER_PRODUCT_ID_1, 9_000);
        assertThat(policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(requested)))
            .contains(requested);

        OrderRefund completed = refund(ORDER_PRODUCT_ID_1, 9_000);
        completed.complete(COMPLETED_AT);
        assertThat(policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(completed)))
            .contains(completed);
    }

    @Test
    void partialActiveOverlap_throwsInProgress() {
        OrderRefund requested = refund(List.of(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2));

        assertError(
            () -> policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(requested)),
            ErrorCode.ORDER_REFUND_IN_PROGRESS
        );
    }

    @Test
    void partialActiveOverlapWinsOverExactActiveAndCompletedRegardlessOfHistoryOrder() {
        OrderRefund partialActive = refund(List.of(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2));
        OrderRefund exactActive = refund(ORDER_PRODUCT_ID_1, 9_000);
        OrderRefund exactCompleted = refund(ORDER_PRODUCT_ID_1, 9_000);
        exactCompleted.complete(COMPLETED_AT);

        assertError(
            () -> policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1),
                List.of(exactActive, exactCompleted, partialActive)),
            ErrorCode.ORDER_REFUND_IN_PROGRESS
        );
        assertError(
            () -> policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1),
                List.of(partialActive, exactActive, exactCompleted)),
            ErrorCode.ORDER_REFUND_IN_PROGRESS
        );
    }

    @Test
    void exactCompletedWinsOverFailedHistories() {
        OrderRefund completed = refund(ORDER_PRODUCT_ID_1, 9_000);
        completed.complete(COMPLETED_AT);
        OrderRefund retryableFailed = refund(ORDER_PRODUCT_ID_1, 9_000);
        retryableFailed.fail("TEMP", "temporary", true, FAILED_AT.plusMinutes(1));
        OrderRefund nonRetryableFailed = refund(ORDER_PRODUCT_ID_1, 9_000);
        nonRetryableFailed.fail("DENIED", "denied", false, FAILED_AT);

        assertThat(policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1),
            List.of(nonRetryableFailed, retryableFailed, completed))).contains(completed);
        assertThat(policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1),
            List.of(completed, nonRetryableFailed, retryableFailed))).contains(completed);
    }

    @Test
    void anyUnknownOverlap_throwsResultUnknown() {
        OrderRefund unknown = refund(ORDER_PRODUCT_ID_1, 9_000);
        unknown.markUnknown(REQUESTED_AT.plusMinutes(10));

        assertError(
            () -> policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(unknown)),
            ErrorCode.ORDER_REFUND_RESULT_UNKNOWN
        );

        OrderRefund newerExactActive = refund(ORDER_PRODUCT_ID_1, 9_000);
        assertError(
            () -> policy.resolve(
                PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(newerExactActive, unknown)
            ),
            ErrorCode.ORDER_REFUND_RESULT_UNKNOWN
        );
    }

    @Test
    void newestFailedOverlap_controlsRetryability() {
        OrderRefund newestRetryable = refund(ORDER_PRODUCT_ID_1, 9_000);
        newestRetryable.fail("TEMP", "temporary", true, FAILED_AT.plusMinutes(1));
        OrderRefund olderNonRetryable = refund(ORDER_PRODUCT_ID_1, 9_000);
        olderNonRetryable.fail("DENIED", "denied", false, FAILED_AT);

        assertThat(policy.resolve(
            PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(newestRetryable, olderNonRetryable)
        )).isEqualTo(Optional.empty());

        assertError(
            () -> policy.resolve(
                PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_1), List.of(olderNonRetryable, newestRetryable)
            ),
            ErrorCode.ORDER_REFUND_RETRY_NOT_ALLOWED
        );
    }

    @Test
    void noOverlapOrPaymentMismatch_permitsNewRequest() {
        OrderRefund existing = refund(ORDER_PRODUCT_ID_1, 9_000);

        assertThat(policy.resolve(PAYMENT_ID, Set.of(ORDER_PRODUCT_ID_2), List.of(existing))).isEmpty();
        assertThat(policy.resolve(UUID.randomUUID(), Set.of(ORDER_PRODUCT_ID_1), List.of(existing))).isEmpty();
    }

    private static OrderRefund refund(UUID id, int amount) {
        return OrderRefund.request(
            ORDER_ID, PAYMENT_ID, BUYER_ID, List.of(paidProduct(id, amount)), REQUESTED_AT
        );
    }

    private static OrderRefund refund(List<UUID> ids) {
        List<OrderProduct> products = ids.stream().map(id -> paidProduct(id, 9_000)).toList();
        return OrderRefund.request(ORDER_ID, PAYMENT_ID, BUYER_ID, products, REQUESTED_AT);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode code) {
        assertThatThrownBy(callable)
            .isInstanceOf(OrderException.class)
            .satisfies(exception -> assertThat(((OrderException) exception).getErrorCode()).isEqualTo(code));
    }
}
