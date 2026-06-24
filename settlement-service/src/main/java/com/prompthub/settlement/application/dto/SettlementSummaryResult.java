package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.util.List;

public record SettlementSummaryResult(List<Card> cards) {

    public record Card(SettlementDisplayStatus status, BigDecimal totalAmount, long count) {
    }
}
