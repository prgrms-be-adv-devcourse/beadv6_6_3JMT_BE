package com.prompthub.user.sellersettlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSettlementListResponseTest {

    private SellerSettlement row() {
        return SellerSettlement.seed(
                UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                2, new BigDecimal("320000.00"), new BigDecimal("260000.00"),
                new BigDecimal("48000.00"), new BigDecimal("0.00"), LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    @Test
    void from_APPROVED항목은_상태라벨과_지급신청_액션을_갖는다() {
        SellerSettlement settlement = row();
        settlement.approve();

        SellerSettlementListResponse.Item out = SellerSettlementListResponse.Item.from(settlement);

        assertThat(out.settlementId()).isEqualTo(settlement.getSettlementId());
        assertThat(out.period()).isEqualTo("2026-06");
        assertThat(out.status()).isEqualTo("APPROVED");
        assertThat(out.statusLabel()).isEqualTo("승인");
        assertThat(out.payoutAmount()).isEqualByComparingTo("260000.00");
        assertThat(out.availableActions()).extracting(SellerSettlementListResponse.Action::type)
                .containsExactly("REQUEST_PAYOUT");
    }

    @Test
    void from_지급신청불가면_액션이_비어있다() {
        SellerSettlement settlement = row();

        SellerSettlementListResponse.Item out = SellerSettlementListResponse.Item.from(settlement);

        assertThat(out.availableActions()).isEmpty();
    }
}
