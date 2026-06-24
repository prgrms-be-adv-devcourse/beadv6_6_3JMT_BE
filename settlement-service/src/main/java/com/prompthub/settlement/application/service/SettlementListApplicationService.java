package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.usecase.GetSettlementListUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 목록(페이징) 조회 구현체.
 *
 * <p>표시 상태 필터·페이징은 {@link SettlementListQueryRepository} 에 위임하고, 조회한
 * {@code Settlement} 를 응답용 항목으로 매핑한다.
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
        List<SettlementListResult.Item> items = page.content().stream()
                .map(SettlementListApplicationService::toItem)
                .toList();
        return new SettlementListResult(items, page.totalElements(), query.page(), query.size());
    }

    private static SettlementListResult.Item toItem(Settlement settlement) {
        return new SettlementListResult.Item(
                settlement.getId(),
                settlement.getSellerId(),
                settlement.getPeriodStart(),
                settlement.getPeriodEnd(),
                settlement.getProductCount(),
                settlement.getTotalAmount(),
                settlement.getFeeTotalAmount(),
                settlement.getSettlementTotalAmount(),
                settlement.displayStatus());
    }
}
