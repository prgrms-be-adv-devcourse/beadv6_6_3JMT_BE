package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class InvalidRejoinTokenException extends BusinessException {

    public InvalidRejoinTokenException() {
        super(UserErrorCode.AUTH_REJOIN_TOKEN_INVALID);
    }
}
