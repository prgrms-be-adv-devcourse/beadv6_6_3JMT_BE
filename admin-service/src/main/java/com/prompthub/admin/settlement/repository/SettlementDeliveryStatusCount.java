package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;

public record SettlementDeliveryStatusCount(
        SettlementDeliveryStatus status,
        long count) {
}
