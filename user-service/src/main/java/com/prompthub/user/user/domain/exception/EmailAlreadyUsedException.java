package com.prompthub.user.user.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class EmailAlreadyUsedException extends BusinessException {

    public EmailAlreadyUsedException() {
        super(UserErrorCode.AUTH_EMAIL_DUPLICATED);
    }
}
