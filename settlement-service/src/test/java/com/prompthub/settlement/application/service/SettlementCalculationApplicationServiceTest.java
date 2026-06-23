package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementCalculationApplicationServiceTest {

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @InjectMocks
    private SettlementCalculationApplicationService service;

    private SettlementSourceLine line(UUID sellerId, String amount) {
        return new SettlementSourceLine(
                UUID.randomUUID(), sellerId, new BigDecimal(amount),
                LocalDateTime.of(2026, 6, 15, 10, 0));
    }

    @Test
    @DisplayName("정산 대상 라인을 기본 수수료율(0.15)로 정산 상세로 변환해 정산을 생성한다")
    void calculate_mapsSourceLinesWithDefaultFeeRate() {
        // given
        UUID sellerId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        given(settlementSourceRepository.findSettleableLines(sellerId, period))
                .willReturn(List.of(line(sellerId, "100.00"), line(sellerId, "200.00")));

        // when
        Settlement settlement = service.calculate(command);

        // then : fee = 15.00 + 30.00, settlement = 85.00 + 170.00
        assertThat(settlement.getSellerId()).isEqualTo(sellerId);
        assertThat(settlement.getProductCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("255.00");
        verify(settlementSourceRepository).findSettleableLines(sellerId, period);
    }

    @Test
    @DisplayName("정산 대상 라인이 없으면 건수·합계가 0인 정산을 생성한다")
    void calculate_noLines_returnsEmptySettlement() {
        // given
        UUID sellerId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        given(settlementSourceRepository.findSettleableLines(sellerId, period))
                .willReturn(List.of());

        // when
        Settlement settlement = service.calculate(command);

        // then
        assertThat(settlement.getProductCount()).isZero();
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
