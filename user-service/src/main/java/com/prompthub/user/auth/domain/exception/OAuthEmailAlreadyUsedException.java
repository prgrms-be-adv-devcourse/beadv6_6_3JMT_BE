package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class OAuthEmailAlreadyUsedException extends BusinessException {

    public OAuthEmailAlreadyUsedException() {
        super(UserErrorCode.AUTH_EMAIL_DUPLICATED);
    }
}
