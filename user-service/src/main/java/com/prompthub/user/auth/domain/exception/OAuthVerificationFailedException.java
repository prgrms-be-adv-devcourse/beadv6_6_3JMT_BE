package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class OAuthVerificationFailedException extends BusinessException {

    public OAuthVerificationFailedException(String reason) {
        super(UserErrorCode.AUTH_OAUTH_VERIFICATION_FAILED, "OAuth 인증에 실패했습니다: " + reason);
    }
}
