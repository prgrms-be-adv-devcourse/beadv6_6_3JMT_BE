package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.SettlementSourceLine;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementSourceRepository {

    void saveAll(List<SettlementSourceLine> lines);

    List<UUID> findExistingEventIds(Collection<UUID> eventIds);

    List<UUID> findSettleableSellerIds(YearMonth period);

    List<SettlementSourceLine> findSettleableLines(UUID sellerId, YearMonth period);

    List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
