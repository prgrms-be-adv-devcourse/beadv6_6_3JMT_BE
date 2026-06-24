package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class TokenExpiredException extends BusinessException {

    public TokenExpiredException() {
        super(UserErrorCode.AUTH_TOKEN_EXPIRED);
    }
}
