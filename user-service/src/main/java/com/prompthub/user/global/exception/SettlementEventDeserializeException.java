package com.prompthub.user.global.exception;

import com.prompthub.exception.BusinessException;

public class SettlementEventDeserializeException extends BusinessException {

    public SettlementEventDeserializeException(String message, Throwable cause) {
        super(UserErrorCode.SETTLEMENT_EVENT_DESERIALIZE_FAILED, message);
        initCause(cause);
    }
}
