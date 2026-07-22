package com.prompthub.user.sellersettlement.domain.repository;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface SellerSettlementRepository {

    SellerSettlement save(SellerSettlement settlement);

    boolean existsBySettlementId(UUID settlementId);

    Optional<SellerSettlement> findBySettlementId(UUID settlementId);

    BigDecimal sumTotalAmountBySeller(UUID sellerId);

    BigDecimal sumPaidSettlementAmountBySeller(UUID sellerId);
}
