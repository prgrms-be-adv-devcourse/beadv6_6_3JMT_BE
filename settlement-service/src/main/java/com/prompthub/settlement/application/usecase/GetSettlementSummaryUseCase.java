package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;

/**
 * 정산 요약 카드 조회 인바운드 포트.
 *
 * <p>공유 골격에서 시그니처를 동결한다(frozen). 요약 세션은 이 포트의 구현체만 채운다.
 */
public interface GetSettlementSummaryUseCase {

    SettlementSummaryResult getSummary();
}
