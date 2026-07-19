package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementSourceRepository {

    void saveAll(List<SettlementSourceLine> lines);

    List<UUID> findExistingEventIds(Collection<UUID> eventIds);

    List<UUID> findSettleableSellerIds(SettlementPeriod period);

    List<SettlementSourceLine> findSettleableLines(UUID sellerId, SettlementPeriod period);

    List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
