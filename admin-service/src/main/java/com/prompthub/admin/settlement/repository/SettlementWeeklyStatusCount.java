package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;

public record SettlementWeeklyStatusCount(
        SettlementDisplayStatus status,
        long count) {
}
