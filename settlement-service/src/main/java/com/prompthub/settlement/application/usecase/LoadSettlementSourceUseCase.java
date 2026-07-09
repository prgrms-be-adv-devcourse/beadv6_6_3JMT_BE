package com.prompthub.settlement.application.usecase;

import java.time.YearMonth;

/**
 * 정산 대상 라인을 order-service 에서 bulk 조회해 SettlementSourceLine 으로 적재하는 유스케이스(#260).
 * 정산 배치 시작 스텝에서 호출한다.
 */
public interface LoadSettlementSourceUseCase {

    /**
     * 정산 기준월의 정산 대상 라인을 조회해 신규 라인만 적재하고, 적재한 건수를 반환한다.
     */
    int load(YearMonth period);
}
