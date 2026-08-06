package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class RefreshTokenReuseDetectedException extends BusinessException {

    public RefreshTokenReuseDetectedException() {
        super(UserErrorCode.AUTH_REFRESH_TOKEN_REUSE_DETECTED);
    }
}
