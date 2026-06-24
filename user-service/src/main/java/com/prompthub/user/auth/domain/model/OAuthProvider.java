package com.prompthub.user.auth.domain.model;

import com.prompthub.user.auth.domain.exception.UnsupportedOAuthProviderException;

public enum OAuthProvider {
    KAKAO,
    NAVER,
    GOOGLE;

    public static OAuthProvider fromString(String value) {
        try {
            return OAuthProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOAuthProviderException(value);
        }
    }
}
