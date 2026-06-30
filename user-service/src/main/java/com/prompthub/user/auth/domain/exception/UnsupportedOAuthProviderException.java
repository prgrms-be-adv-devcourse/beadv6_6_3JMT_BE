package com.prompthub.user.auth.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class UnsupportedOAuthProviderException extends BusinessException {

    public UnsupportedOAuthProviderException(String provider) {
        super(UserErrorCode.UNSUPPORTED_OAUTH_PROVIDER, "지원하지 않는 OAuth 공급자입니다: " + provider);
    }
}
