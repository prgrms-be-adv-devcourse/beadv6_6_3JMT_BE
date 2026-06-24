package com.prompthub.settlement.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;

public class SettlementException extends BusinessException {

    public SettlementException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SettlementException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public SettlementException(ErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}
