package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import com.prompthub.settlement.domain.model.source.SettlementSourceLineType;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSourceAggregateRepositoryAdapterTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26));

    @Mock
    private SettlementSourceLineJpaRepository jpaRepository;

    @InjectMocks
    private SettlementSourceAggregateRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        given(jpaRepository.aggregateByPeriod(
                LocalDateTime.of(2026, 7, 20, 0, 0),
                LocalDateTime.of(2026, 7, 27, 0, 0)))
                .willReturn(List.of(
                        new SettlementSourceLineTypeAggregate(
                                SettlementSourceLineType.PAID,
                                3,
                                new BigDecimal("6000")),
                        new SettlementSourceLineTypeAggregate(
                                SettlementSourceLineType.REFUND,
                                2,
                                new BigDecimal("1500"))));
    }

    @Test
    @DisplayName("정산 여부와 무관한 기간 전체 원천의 유형별 건수·금액을 집계한다")
    void aggregate_mapsPaidAndRefundRows() {
        SettlementSourceAggregate result = adapter.aggregate(PERIOD);

        assertThat(result.paidCount()).isEqualTo(3);
        assertThat(result.paidAmount()).isEqualByComparingTo("6000");
        assertThat(result.refundCount()).isEqualTo(2);
        assertThat(result.refundAmount()).isEqualByComparingTo("1500");
        then(jpaRepository).should().aggregateByPeriod(
                LocalDateTime.of(2026, 7, 20, 0, 0),
                LocalDateTime.of(2026, 7, 27, 0, 0));
    }

    @Test
    @DisplayName("특정 유형 집계가 없으면 해당 건수와 금액을 0으로 채운다")
    void aggregate_missingRefundRow_fillsZero() {
        given(jpaRepository.aggregateByPeriod(
                LocalDateTime.of(2026, 7, 20, 0, 0),
                LocalDateTime.of(2026, 7, 27, 0, 0)))
                .willReturn(List.of(new SettlementSourceLineTypeAggregate(
                        SettlementSourceLineType.PAID,
                        1,
                        new BigDecimal("1000"))));

        SettlementSourceAggregate result = adapter.aggregate(PERIOD);

        assertThat(result.paidCount()).isEqualTo(1);
        assertThat(result.paidAmount()).isEqualByComparingTo("1000");
        assertThat(result.refundCount()).isZero();
        assertThat(result.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
