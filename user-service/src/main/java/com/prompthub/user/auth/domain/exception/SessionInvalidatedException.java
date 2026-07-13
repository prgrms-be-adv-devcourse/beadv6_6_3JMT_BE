package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class SessionInvalidatedException extends BusinessException {

    public SessionInvalidatedException() {
        super(UserErrorCode.AUTH_SESSION_INVALIDATED);
    }
}
