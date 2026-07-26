package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.application.service.SettlementChangeCalculator.CountChange;
import com.prompthub.user.sellersettlement.application.service.SettlementChangeCalculator.DecimalChange;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("정산 분석 증감 계산")
class SettlementChangeCalculatorTest {

    private final SettlementChangeCalculator calculator = new SettlementChangeCalculator();

    @Test
    @DisplayName("건수 차이도 같은 증감률 규칙을 적용한다")
    void calculatesCountChange() {
        CountChange result = calculator.countChange(80, 100);

        assertThat(result.difference()).isEqualTo(-20);
        assertThat(result.changeRatePercent()).isEqualTo("-20.00");
        assertThat(result.comparable()).isTrue();
    }

    @ParameterizedTest(name = "현재 {0}, 비교 {1}이면 차이 {2}, 증감률 {3}, 비교가능 {4}")
    @CsvSource({
            "120, 100, 20, 20.00, true",
            "0, 0, 0, 0.00, true",
            "100, 0, 100, '', false",
            "80, 100, -20, -20.00, true"
    })
    @DisplayName("0 기준을 포함한 십진 증감률 규칙을 적용한다")
    void calculatesDecimalChanges(
            String current,
            String comparison,
            String difference,
            String rate,
            boolean comparable) {
        DecimalChange result = calculator.decimalChange(
                new BigDecimal(current), new BigDecimal(comparison));

        assertThat(result.difference()).isEqualByComparingTo(difference);
        assertThat(result.changeRatePercent()).isEqualTo(rate);
        assertThat(result.comparable()).isEqualTo(comparable);
    }
}
