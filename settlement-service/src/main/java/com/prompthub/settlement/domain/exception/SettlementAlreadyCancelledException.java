package com.prompthub.settlement.domain.exception;

import java.util.UUID;

public class SettlementAlreadyCancelledException extends RuntimeException {

    public SettlementAlreadyCancelledException(UUID settlementId) {
        super("이미 취소된 정산입니다. settlementId=" + settlementId);
    }
}
