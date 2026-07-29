package com.prompthub.admin.settlement.dto;

import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import java.util.UUID;

public record SettlementDeliveryListQuery(
        SettlementDeliveryStatus status,
        boolean problemOnly,
        UUID identifier,
        int page,
        int size) {
}
