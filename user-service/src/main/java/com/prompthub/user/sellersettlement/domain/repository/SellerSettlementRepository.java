package com.prompthub.user.sellersettlement.domain.repository;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerSettlementRepository {

    SellerSettlement save(SellerSettlement settlement);

    boolean existsBySettlementId(UUID settlementId);

    Optional<SellerSettlement> findBySettlementId(UUID settlementId);

    SellerSettlementPage findPageBySeller(
            UUID sellerId, SettlementDisplayStatus status, YearMonth period, int page, int size);

    BigDecimal sumTotalAmountBySeller(UUID sellerId);

    BigDecimal sumPaidSettlementAmountBySeller(UUID sellerId);

    record SellerSettlementPage(List<SellerSettlement> content, long totalElements) {
    }
}
