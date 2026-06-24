package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.usecase.GetSettlementListUseCase;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 목록(페이징) 조회 구현체.
 *
 * <p>표시 상태 필터·페이징은 {@link SettlementListQueryRepository} 에 위임하고, 조회 결과를
 * {@link SettlementListResult#from} 으로 변환한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SettlementListApplicationService implements GetSettlementListUseCase {

    private final SettlementListQueryRepository settlementListQueryRepository;

    @Override
    public SettlementListResult getList(SettlementListQuery query) {
        SettlementListQueryRepository.SettlementPage page =
                settlementListQueryRepository.findPage(query.status(), query.page(), query.size());
        return SettlementListResult.from(page.content(), page.totalElements(), query.page(), query.size());
    }
}
