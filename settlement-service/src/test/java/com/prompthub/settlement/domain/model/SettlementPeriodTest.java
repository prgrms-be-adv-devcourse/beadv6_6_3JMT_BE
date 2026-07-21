package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SettlementPeriodTest {

    @Test
    void previousWeek_returnsPreviousMondayThroughSunday() {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.of(2026, 7, 20));

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(period.startInclusive()).isEqualTo(LocalDateTime.of(2026, 7, 13, 0, 0));
        assertThat(period.endExclusive()).isEqualTo(LocalDateTime.of(2026, 7, 20, 0, 0));
    }

    @Test
    void previousWeek_handlesYearBoundary() {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.of(2027, 1, 4));

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2027, 1, 3));
    }

    @Test
    void of_rejectsNonMondayStart() {
        assertThatThrownBy(() -> SettlementPeriod.of(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("월요일");
    }

    @Test
    void of_rejectsRangeOtherThanSevenDays() {
        assertThatThrownBy(() -> SettlementPeriod.of(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 18)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일요일");
    }
}
