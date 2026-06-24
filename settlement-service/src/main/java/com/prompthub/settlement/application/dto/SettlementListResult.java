package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SettlementListResult(
        List<Item> items,
        long totalElements,
        int page,
        int size
) {

    public static SettlementListResult from(List<Settlement> settlements, long totalElements, int page, int size) {
        List<Item> items = settlements.stream().map(Item::from).toList();
        return new SettlementListResult(items, totalElements, page, size);
    }

    public record Item(
            UUID settlementId,
            UUID sellerId,
            LocalDate periodStart,
            LocalDate periodEnd,
            int productCount,
            BigDecimal totalAmount,
            BigDecimal feeTotalAmount,
            BigDecimal settlementTotalAmount,
            SettlementDisplayStatus displayStatus
    ) {

        public static Item from(Settlement settlement) {
            return new Item(
                    settlement.getId(),
                    settlement.getSellerId(),
                    settlement.getPeriodStart(),
                    settlement.getPeriodEnd(),
                    settlement.getProductCount(),
                    settlement.getTotalAmount(),
                    settlement.getFeeTotalAmount(),
                    settlement.getSettlementTotalAmount(),
                    settlement.displayStatus());
        }
    }
}
