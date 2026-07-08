package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerSettlementRepositoryAdapter implements SellerSettlementRepository {

    private final SellerSettlementJpaRepository jpaRepository;

    @Override
    public SellerSettlement save(SellerSettlement settlement) {
        return jpaRepository.save(settlement);
    }

    @Override
    public boolean existsBySettlementId(UUID settlementId) {
        return jpaRepository.existsBySettlementId(settlementId);
    }

    @Override
    public Optional<SellerSettlement> findBySettlementId(UUID settlementId) {
        return jpaRepository.findBySettlementId(settlementId);
    }

    @Override
    public SellerSettlementPage findPageBySeller(
            UUID sellerId, SettlementDisplayStatus status, YearMonth period, int page, int size) {
        LocalDate periodStart = period == null ? null : period.atDay(1);
        LocalDate periodEnd = period == null ? null : period.atEndOfMonth();
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodStart")
                .and(Sort.by(Sort.Direction.ASC, "sellerSettlementId")));
        Page<SellerSettlement> result = jpaRepository.findPageBySeller(
                sellerId, status, periodStart, periodEnd, pageable);
        return new SellerSettlementPage(result.getContent(), result.getTotalElements());
    }
}
