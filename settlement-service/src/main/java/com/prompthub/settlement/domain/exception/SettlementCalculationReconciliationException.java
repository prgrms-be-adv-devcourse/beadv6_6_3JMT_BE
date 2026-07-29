package com.prompthub.settlement.domain.exception;

import java.util.List;
import java.util.UUID;

public class SettlementCalculationReconciliationException
        extends RuntimeException {

    public SettlementCalculationReconciliationException(
            List<UUID> mismatchedSettlementIds) {
        super("정산 계산 대사 불일치. settlementIds="
                + List.copyOf(mismatchedSettlementIds));
    }
}
