package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerSettlementRepositoryAdapter implements SellerSettlementRepository {

    private static final List<SettlementDisplayStatus> REVENUE_STATUSES = List.of(
            SettlementDisplayStatus.WAITING,
            SettlementDisplayStatus.APPROVAL_ON_HOLD,
            SettlementDisplayStatus.APPROVED,
            SettlementDisplayStatus.PAYOUT_REQUESTED,
            SettlementDisplayStatus.PAYOUT_ON_HOLD,
            SettlementDisplayStatus.PAID);
    private static final List<SettlementDisplayStatus> APPROVED_STATUSES = List.of(
            SettlementDisplayStatus.APPROVED,
            SettlementDisplayStatus.PAYOUT_REQUESTED,
            SettlementDisplayStatus.PAYOUT_ON_HOLD,
            SettlementDisplayStatus.PAID);

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
    public Optional<SellerSettlement> findByDeliveryRequestId(UUID deliveryRequestId) {
        return jpaRepository.findByDeliveryRequestId(deliveryRequestId);
    }

    @Override
    public BigDecimal sumTotalAmountBySeller(UUID sellerId) {
        return jpaRepository.sumTotalAmountBySellerAndStatusIn(
                sellerId, REVENUE_STATUSES);
    }

    @Override
    public BigDecimal sumApprovedSettlementAmountBySeller(UUID sellerId) {
        return jpaRepository.sumSettlementTotalAmountBySellerAndStatusIn(
                sellerId, APPROVED_STATUSES);
    }
}
