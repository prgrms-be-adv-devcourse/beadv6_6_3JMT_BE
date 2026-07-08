package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record SellerSettlementListResult(
        List<Item> items,
        long totalElements,
        int page,
        int size
) {

    public static SellerSettlementListResult from(
            SellerSettlementRepository.SellerSettlementPage page, int pageNo, int size) {
        List<Item> items = page.content().stream().map(Item::from).toList();
        return new SellerSettlementListResult(items, page.totalElements(), pageNo, size);
    }

    public record Item(
            UUID settlementId,
            YearMonth period,
            LocalDate periodStart,
            LocalDate periodEnd,
            int salesCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal refundAmount,
            BigDecimal payoutAmount,
            SettlementDisplayStatus status,
            boolean canRequestPayout
    ) {

        public static Item from(SellerSettlement s) {
            return new Item(
                    s.getSettlementId(),
                    YearMonth.from(s.getPeriodStart()),
                    s.getPeriodStart(),
                    s.getPeriodEnd(),
                    s.getProductCount(),
                    s.getTotalAmount(),
                    s.getFeeTotalAmount(),
                    s.getRefundAmount(),
                    s.getSettlementTotalAmount(),
                    s.getStatus(),
                    s.canRequestPayout());
        }
    }
}
