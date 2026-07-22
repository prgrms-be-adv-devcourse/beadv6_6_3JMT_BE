package com.prompthub.user.sellersettlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyKey;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyPage;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementMonthlyResponse.StatusCount;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSettlementListResponseTest {

    @Test
    void from_월별합계와_상태건수를_enum순서로_변환한다() {
        MonthlyKey key = new MonthlyKey(UUID.randomUUID(), YearMonth.of(2026, 7));
        MonthlyAggregate aggregate = new MonthlyAggregate(
                key, 3, 2, 22,
                bd("2200000"), bd("330000"), bd("100000"), bd("1770000"));
        List<MonthlyStatusCount> counts = List.of(
                new MonthlyStatusCount(key, SettlementDisplayStatus.CANCELLED, 1),
                new MonthlyStatusCount(key, SettlementDisplayStatus.APPROVED, 1),
                new MonthlyStatusCount(key, SettlementDisplayStatus.PAID, 1));

        SellerSettlementListResponse response = SellerSettlementListResponse.from(
                new MonthlyPage(List.of(aggregate), 1), counts, 0, 10);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.settlementMonth()).isEqualTo("2026-07");
            assertThat(item.weeklySettlementCount()).isEqualTo(3);
            assertThat(item.aggregatedSettlementCount()).isEqualTo(2);
            assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
            assertThat(item.statusCounts()).extracting(StatusCount::status)
                    .containsExactly("APPROVED", "PAID", "CANCELLED");
        });
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
