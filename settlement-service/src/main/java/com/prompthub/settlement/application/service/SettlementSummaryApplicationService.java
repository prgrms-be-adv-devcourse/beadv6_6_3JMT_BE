package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.application.usecase.GetSettlementSummaryUseCase;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 요약 카드 조회 구현체.
 *
 * <p>공유 골격이 깔아둔 스텁이다. <b>요약 세션이 소유</b>해 집계 로직으로 채운다.
 * (집계 권장안: GROUP BY settlementStatus, payoutStatus 후 SettlementDisplayStatus.from 으로 접기 — 설계 문서 §6)
 */
@Service
@Transactional(readOnly = true)
public class SettlementSummaryApplicationService implements GetSettlementSummaryUseCase {

    @Override
    public SettlementSummaryResult getSummary() {
        // TODO(요약 세션): 집계 쿼리로 카드 구성 채우기. 현재는 골격 스텁(빈 결과).
        return new SettlementSummaryResult(List.of());
    }
}
