package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.usecase.GetSettlementListUseCase;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 목록(페이징) 조회 구현체.
 *
 * <p>공유 골격이 깔아둔 스텁이다. <b>목록 세션이 소유</b>해 페이징·필터 로직으로 채운다.
 * (필터 권장안: 표시 상태 → settlementStatus/payoutStatus 술어 변환은 설계 문서 §2 표를 단일 출처로 — §6)
 */
@Service
@Transactional(readOnly = true)
public class SettlementListApplicationService implements GetSettlementListUseCase {

    @Override
    public SettlementListResult getList(SettlementListQuery query) {
        // TODO(목록 세션): 페이징·상태 필터 쿼리로 채우기. 현재는 골격 스텁(빈 페이지).
        return new SettlementListResult(List.of(), 0L, query.page(), query.size());
    }
}
