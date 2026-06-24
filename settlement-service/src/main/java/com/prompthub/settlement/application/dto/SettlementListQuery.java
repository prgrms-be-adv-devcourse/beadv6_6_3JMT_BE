package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;

public record SettlementListQuery(SettlementDisplayStatus status, int page, int size) {
}
