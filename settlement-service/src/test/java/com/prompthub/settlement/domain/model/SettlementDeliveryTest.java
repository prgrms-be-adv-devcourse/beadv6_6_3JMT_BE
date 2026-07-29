package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDeliveryTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 29, 10, 0);

    @Test
    @DisplayName("계산된 Delivery는 시도를 누적하고 대사 완료 상태가 된다")
    void recordsAttemptAndReconciles() {
        SettlementDelivery delivery = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        delivery.recordAttempt(NOW);
        delivery.reconcile(NOW.plusSeconds(1));

        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getFirstAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getStatus()).isEqualTo(SettlementDeliveryStatus.RECONCILED);
        assertThat(delivery.getStatusReason()).isNull();
        assertThat(delivery.getReconciledAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("실패와 불일치는 사유를 보존하고 terminal 상태가 된다")
    void recordsFailureAndMismatchReason() {
        SettlementDelivery failed = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        failed.recordAttempt(NOW);
        failed.fail("gRPC UNAVAILABLE: attempts=1");

        assertThat(failed.getStatus())
                .isEqualTo(SettlementDeliveryStatus.DELIVERY_FAILED);
        assertThat(failed.getStatusReason()).contains("UNAVAILABLE");
        assertThatThrownBy(() -> failed.recordAttempt(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        SettlementDelivery mismatch = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        mismatch.mismatch("feeTotalAmount 불일치: mismatchCount=1");
        assertThat(mismatch.getStatus())
                .isEqualTo(SettlementDeliveryStatus.MISMATCH);
        assertThat(mismatch.getStatusReason()).contains("mismatchCount=1");
    }

    @Test
    @DisplayName("전달 실패 건은 누적 시도 정보를 유지한 채 수동 재전송을 준비한다")
    void preparesManualRetryWithoutResettingAttempts() {
        UUID deliveryRequestId = UUID.randomUUID();
        SettlementDelivery delivery = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), deliveryRequestId);
        delivery.recordAttempt(NOW);
        delivery.recordAttempt(NOW.plusSeconds(1));
        delivery.recordAttempt(NOW.plusSeconds(2));
        delivery.fail("gRPC UNAVAILABLE: attempts=3");

        int previousAttempts = delivery.prepareManualRetry();

        assertThat(previousAttempts).isEqualTo(3);
        assertThat(delivery.getStatus())
                .isEqualTo(SettlementDeliveryStatus.DELIVERY_FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getDeliveryRequestId()).isEqualTo(deliveryRequestId);
        assertThat(delivery.getStatusReason())
                .isEqualTo("gRPC UNAVAILABLE: attempts=3");
        assertThat(delivery.getFirstAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    @DisplayName("전달 실패가 아닌 건은 수동 재전송을 준비할 수 없다")
    void rejectsManualRetryForNonFailedDelivery() {
        SettlementDelivery delivery = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(delivery::prepareManualRetry)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("수동 재전송 도중 예외가 발생하면 다시 전달 실패 상태로 복구한다")
    void restoresFailureWhenManualRetryCrashes() {
        SettlementDelivery delivery = SettlementDelivery.calculated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        delivery.recordAttempt(NOW);
        delivery.fail("gRPC UNAVAILABLE: attempts=1");
        delivery.prepareManualRetry();
        delivery.recordManualRetryAttempt(NOW.plusSeconds(1));
        delivery.recordManualRetryFailure(
                "재전송 실행 오류: IllegalStateException");

        assertThat(delivery.getStatus())
                .isEqualTo(SettlementDeliveryStatus.DELIVERY_FAILED);
        assertThat(delivery.getStatusReason())
                .isEqualTo("재전송 실행 오류: IllegalStateException");
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
    }
}
