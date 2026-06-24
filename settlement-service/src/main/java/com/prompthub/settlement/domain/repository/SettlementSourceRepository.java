package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.SettlementSourceLine;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface SettlementSourceRepository {

    List<UUID> findSettleableSellerIds(YearMonth period);

    List<SettlementSourceLine> findSettleableLines(UUID sellerId, YearMonth period);

    List<SettlementSourceLine> findBySettlementId(UUID settlementId);

    long countPaidBySeller(UUID sellerId);

    BigDecimal sumPaidAmountBySeller(UUID sellerId);
}
