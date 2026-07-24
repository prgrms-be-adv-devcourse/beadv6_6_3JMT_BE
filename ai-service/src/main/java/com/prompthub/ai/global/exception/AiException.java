package com.prompthub.ai.global.exception;

import com.prompthub.exception.BusinessException;

public class AiException extends BusinessException {

    public AiException(AiErrorCode errorCode) {
        super(errorCode);
    }

    public AiException(AiErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}
