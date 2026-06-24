package com.prompthub.settlement.domain.exception;

import java.util.UUID;

public class SettlementSourceLineAlreadySettledException extends RuntimeException {

    public SettlementSourceLineAlreadySettledException(UUID settlementId) {
        super("이미 정산에 포함된 소스 라인입니다. settlementId=" + settlementId);
    }
}
