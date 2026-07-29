package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import java.math.BigDecimal;

public record SettlementSourceLineTypeAggregate(
        SettlementSourceLineType lineType,
        long lineCount,
        BigDecimal totalAmount) {
}
