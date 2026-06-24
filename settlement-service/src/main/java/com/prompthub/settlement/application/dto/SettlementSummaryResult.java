package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * 정산 요약 카드 결과.
 *
 * <p>씨앗(seed) 형태다. 카드 4종(정산대기·승인완료·지급보류·지급완료) 그룹 구성은 요약 세션이 확정한다.
 * (공유 골격 설계 문서 §4-2 / §6)
 */
public record SettlementSummaryResult(List<Card> cards) {

    public record Card(SettlementDisplayStatus status, BigDecimal totalAmount, long count) {
    }
}
