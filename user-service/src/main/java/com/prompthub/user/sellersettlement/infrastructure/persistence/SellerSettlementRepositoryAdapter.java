package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    public BigDecimal sumTotalAmountBySeller(UUID sellerId) {
        return jpaRepository.sumTotalAmountBySellerAndStatus(
                sellerId, SettlementDisplayStatus.PAID);
    }

    @Override
    public BigDecimal sumPaidSettlementAmountBySeller(UUID sellerId) {
        return jpaRepository.sumSettlementTotalAmountBySellerAndStatus(
                sellerId, SettlementDisplayStatus.PAID);
    }
}
