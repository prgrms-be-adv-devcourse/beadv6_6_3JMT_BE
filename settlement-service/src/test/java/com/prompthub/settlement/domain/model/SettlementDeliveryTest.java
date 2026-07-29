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
}
