package com.prompthub.user.sellersettlement.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class SettlementChangeCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public CountChange countChange(long current, long comparison) {
        long difference = current - comparison;
        Rate rate = rate(BigDecimal.valueOf(difference), BigDecimal.valueOf(comparison));
        return new CountChange(difference, rate.changeRatePercent(), rate.comparable());
    }

    public DecimalChange decimalChange(BigDecimal current, BigDecimal comparison) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(comparison);
        BigDecimal difference = current.subtract(comparison);
        Rate rate = rate(difference, comparison);
        return new DecimalChange(difference, rate.changeRatePercent(), rate.comparable());
    }

    private Rate rate(BigDecimal difference, BigDecimal comparison) {
        if (comparison.compareTo(BigDecimal.ZERO) == 0) {
            if (difference.compareTo(BigDecimal.ZERO) == 0) {
                return new Rate("0.00", true);
            }
            return new Rate("", false);
        }
        String changeRatePercent = difference
                .multiply(ONE_HUNDRED)
                .divide(comparison.abs(), 2, RoundingMode.HALF_UP)
                .toPlainString();
        return new Rate(changeRatePercent, true);
    }

    public record CountChange(long difference, String changeRatePercent, boolean comparable) {
    }

    public record DecimalChange(
            BigDecimal difference,
            String changeRatePercent,
            boolean comparable) {
    }

    private record Rate(String changeRatePercent, boolean comparable) {
    }
}
