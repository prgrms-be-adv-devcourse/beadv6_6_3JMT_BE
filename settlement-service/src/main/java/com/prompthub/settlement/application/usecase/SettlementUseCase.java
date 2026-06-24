package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult;

/**
 * 정산(Settlement) 애그리거트 조회 인바운드 포트.
 *
 * <p>화면/행위가 아니라 <b>Settlement 엔티티 단위</b>로 조회 연산을 한 포트에 모은다.
 * 요약 카드와 목록은 같은 애그리거트의 조회라 함께 둔다.
 */
public interface SettlementUseCase {

    SettlementSummaryResult getSummary();

    SettlementListResult getList(SettlementListQuery query);
}
