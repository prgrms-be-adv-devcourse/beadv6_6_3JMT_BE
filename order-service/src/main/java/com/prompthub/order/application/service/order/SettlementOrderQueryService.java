package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.application.usecase.SettlementOrderQueryUseCase;
import com.prompthub.order.domain.repository.SettlementOrderQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementOrderQueryService implements SettlementOrderQueryUseCase {

    private final SettlementOrderQueryRepository repository;

    @Override
    public List<SettleableLineResult> getSettleableLines(LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(periodStart, "정산 시작일은 필수입니다.");
        Objects.requireNonNull(periodEnd, "정산 종료일은 필수입니다.");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("정산 종료일은 시작일보다 빠를 수 없습니다.");
        }
        LocalDateTime startInclusive = periodStart.atStartOfDay();
        LocalDateTime endExclusive = periodEnd.plusDays(1).atStartOfDay();
        return repository.findSettleableLines(startInclusive, endExclusive);
    }
}
