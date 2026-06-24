package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSourceRepositoryAdapter implements SettlementSourceRepository {

    private final SettlementSourceLineJpaRepository jpaRepository;

    @Override
    public List<UUID> findSettleableSellerIds(YearMonth period) {
        return jpaRepository.findSettleableSellerIds(startOf(period), endOf(period));
    }

    @Override
    public List<SettlementSourceLine> findSettleableLines(UUID sellerId, YearMonth period) {
        return jpaRepository.findSettleableLines(sellerId, startOf(period), endOf(period));
    }

    private LocalDateTime startOf(YearMonth period) {
        return period.atDay(1).atStartOfDay();
    }

    private LocalDateTime endOf(YearMonth period) {
        return period.plusMonths(1).atDay(1).atStartOfDay();
    }
}
