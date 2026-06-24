package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 정산 목록(페이징) 결과.
 *
 * <p>씨앗(seed) 형태다. 구체 항목 필드는 목록 세션이 확정한다. (공유 골격 설계 문서 §4-2 / §6)
 *
 * <p>판매자명은 이번 범위에서 제외하고 {@code sellerId} 만 담는다. 판매자명 연동은 추후 작업이다. (§7)
 */
public record SettlementListResult(
        List<Item> items,
        long totalElements,
        int page,
        int size
) {

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
    }
}
