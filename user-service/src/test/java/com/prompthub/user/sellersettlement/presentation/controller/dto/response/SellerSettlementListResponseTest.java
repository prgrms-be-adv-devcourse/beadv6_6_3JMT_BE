package com.prompthub.user.sellersettlement.presentation.controller.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListResult;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSettlementListResponseTest {

    @Test
    void from_APPROVED항목은_상태라벨과_지급신청_액션을_갖는다() {
        UUID settlementId = UUID.randomUUID();
        SellerSettlementListResult.Item item = new SellerSettlementListResult.Item(
                settlementId, YearMonth.of(2026, 6),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                2, new BigDecimal("320000.00"), new BigDecimal("48000.00"),
                new BigDecimal("0.00"), new BigDecimal("260000.00"),
                SettlementDisplayStatus.APPROVED, true);
        SellerSettlementListResult result = new SellerSettlementListResult(List.of(item), 1, 0, 10);

        SellerSettlementListResponse response = SellerSettlementListResponse.from(result);

        assertThat(response.totalElements()).isEqualTo(1);
        SellerSettlementListResponse.Item out = response.items().get(0);
        assertThat(out.settlementId()).isEqualTo(settlementId);
        assertThat(out.period()).isEqualTo("2026-06");
        assertThat(out.status()).isEqualTo("APPROVED");
        assertThat(out.statusLabel()).isEqualTo("승인");
        assertThat(out.availableActions()).extracting(SellerSettlementListResponse.Action::type)
                .containsExactly("REQUEST_PAYOUT");
    }

    @Test
    void from_지급신청불가면_액션이_비어있다() {
        SellerSettlementListResult.Item item = new SellerSettlementListResult.Item(
                UUID.randomUUID(), YearMonth.of(2026, 6),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                2, new BigDecimal("320000.00"), new BigDecimal("48000.00"),
                new BigDecimal("0.00"), new BigDecimal("260000.00"),
                SettlementDisplayStatus.WAITING, false);
        SellerSettlementListResult result = new SellerSettlementListResult(List.of(item), 1, 0, 10);

        SellerSettlementListResponse response = SellerSettlementListResponse.from(result);

        assertThat(response.items().get(0).availableActions()).isEmpty();
    }
}
