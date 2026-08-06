package com.prompthub.user.auth.application.client;

import com.prompthub.user.auth.application.dto.OAuthUserInfo;

public interface KakaoUserInfoClient {
    OAuthUserInfo fetchUserInfo(String accessToken);
}
