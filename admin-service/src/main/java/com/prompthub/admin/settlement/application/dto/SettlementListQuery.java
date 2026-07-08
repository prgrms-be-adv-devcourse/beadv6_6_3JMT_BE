package com.prompthub.admin.settlement.application.dto;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;

public record SettlementListQuery(SettlementDisplayStatus status, int page, int size) {
}
