package com.prompthub.user.sellersettlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSettlementSeedTest {

    @Test
    void seed_스냅샷을_복사하고_초기상태는_WAITING() {
        UUID settlementId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 7, 1, 4, 0);

        SellerSettlement s = SellerSettlement.seedV1(
                settlementId, sellerId,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                3, new BigDecimal("320000.00"), new BigDecimal("260000.00"),
                new BigDecimal("48000.00"), new BigDecimal("0.00"), calculatedAt);

        assertThat(s.getSellerSettlementId()).isNotNull();
        assertThat(s.getSettlementId()).isEqualTo(settlementId);
        assertThat(s.getSellerId()).isEqualTo(sellerId);
        assertThat(s.getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(s.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(s.getProductCount()).isEqualTo(3);
        assertThat(s.getTotalAmount()).isEqualByComparingTo("320000.00");
        assertThat(s.getSettlementTotalAmount()).isEqualByComparingTo("260000.00");
        assertThat(s.getFeeTotalAmount()).isEqualByComparingTo("48000.00");
        assertThat(s.getRefundAmount()).isEqualByComparingTo("0.00");
        assertThat(s.getCalculatedAt()).isEqualTo(calculatedAt);
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(s.getPayloadVersion()).isEqualTo((short) 1);
        assertThat(s.getDetails()).isEmpty();
    }
}
