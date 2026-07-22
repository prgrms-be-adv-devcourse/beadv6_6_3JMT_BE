package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyKey;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyPage;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementDetailResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementMonthlyResponse.Action;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementMonthlyResponse.StatusCount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementQueryServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @Mock
    private SellerSettlementQueryRepository sellerSettlementQueryRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    @Test
    void getMySettlements_월별페이지와_상태건수를_응답으로_조립한다() {
        UUID sellerId = UUID.randomUUID();
        MonthlyKey key = new MonthlyKey(sellerId, YearMonth.of(2026, 7));
        MonthlyAggregate aggregate = new MonthlyAggregate(
                key, 3, 2, 22,
                bd("2200000"), bd("330000"), bd("100000"), bd("1770000"));
        given(sellerSettlementQueryRepository.findMonthlyPage(
                sellerId, null, null, 0, 10))
                .willReturn(new MonthlyPage(List.of(aggregate), 1));
        given(sellerSettlementQueryRepository.findStatusCounts(List.of(key)))
                .willReturn(List.of(new MonthlyStatusCount(
                        key, SettlementDisplayStatus.APPROVED, 1)));

        SellerSettlementListResponse response = service.getMySettlements(
                new SellerSettlementListQuery(sellerId, null, null, 0, 10));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.settlementMonth()).isEqualTo("2026-07");
            assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
            assertThat(item.statusCounts())
                    .extracting(StatusCount::status)
                    .containsExactly("APPROVED");
        });
    }

    @Test
    void getMySettlementMonth_주간행과_액션을_내려준다() {
        UUID sellerId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        MonthlyKey key = new MonthlyKey(sellerId, month);
        MonthlyAggregate aggregate = new MonthlyAggregate(
                key, 1, 1, 10,
                bd("1000000"), bd("150000"), bd("0"), bd("850000"));
        SellerSettlement weekly = approvedRow(sellerId, LocalDate.of(2026, 6, 29));
        given(sellerSettlementQueryRepository.findMonthlyAggregate(sellerId, month))
                .willReturn(Optional.of(aggregate));
        given(sellerSettlementQueryRepository.findStatusCounts(List.of(key)))
                .willReturn(List.of(new MonthlyStatusCount(
                        key, SettlementDisplayStatus.APPROVED, 1)));
        given(sellerSettlementQueryRepository.findWeeklySettlements(sellerId, month))
                .willReturn(List.of(weekly));

        SellerSettlementDetailResponse response = service.getMySettlementMonth(sellerId, month);

        assertThat(response.weeklySettlements()).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(weekly.getSettlementId());
            assertThat(item.availableActions())
                    .extracting(Action::type)
                    .containsExactly("REQUEST_PAYOUT");
        });
    }

    @Test
    void getMySettlementMonth_본인월이_없으면_404예외다() {
        UUID sellerId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        given(sellerSettlementQueryRepository.findMonthlyAggregate(sellerId, month))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMySettlementMonth(sellerId, month))
                .isInstanceOf(SellerSettlementNotFoundException.class);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private SellerSettlement approvedRow(UUID sellerId, LocalDate periodStart) {
        SellerSettlement settlement = SellerSettlement.seed(
                UUID.randomUUID(), sellerId, periodStart, periodStart.plusDays(6), 10,
                bd("1000000"), bd("850000"), bd("150000"), bd("0"),
                periodStart.plusDays(7).atStartOfDay());
        settlement.approve();
        return settlement;
    }
}
