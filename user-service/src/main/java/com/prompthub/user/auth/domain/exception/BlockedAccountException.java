package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class BlockedAccountException extends BusinessException {

    public BlockedAccountException() {
        super(UserErrorCode.AUTH_FORBIDDEN);
    }
}
