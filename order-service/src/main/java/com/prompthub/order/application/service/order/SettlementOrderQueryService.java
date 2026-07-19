package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.application.usecase.SettlementOrderQueryUseCase;
import com.prompthub.order.domain.repository.SettlementOrderQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementOrderQueryService implements SettlementOrderQueryUseCase {

    private final SettlementOrderQueryRepository repository;

    @Override
    public List<SettleableLineResult> getSettleableLines(YearMonth period) {
        LocalDateTime startInclusive = period.atDay(1).atStartOfDay();
        LocalDateTime endExclusive = period.plusMonths(1).atDay(1).atStartOfDay();
        return repository.findSettleableLines(startInclusive, endExclusive);
    }
}
