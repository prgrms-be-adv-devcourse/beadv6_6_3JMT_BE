package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;

public record SettlementWeeklyStatusCount(
        SettlementDisplayStatus status,
        long count) {
}
