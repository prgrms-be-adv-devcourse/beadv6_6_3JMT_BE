package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;

/**
 * 정산 목록(페이징) 조회 인바운드 포트.
 *
 * <p>공유 골격에서 시그니처를 동결한다(frozen). 목록 세션은 이 포트의 구현체만 채운다.
 */
public interface GetSettlementListUseCase {

    SettlementListResult getList(SettlementListQuery query);
}
