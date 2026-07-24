package com.prompthub.user.global.exception;

import com.prompthub.exception.BusinessException;

public class SettlementEventContractViolationException extends BusinessException {

    public SettlementEventContractViolationException(String message, Throwable cause) {
        super(UserErrorCode.SETTLEMENT_EVENT_CONTRACT_VIOLATION, message);
        initCause(cause);
    }
}
