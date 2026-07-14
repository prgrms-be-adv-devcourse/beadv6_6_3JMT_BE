package com.prompthub.user.auth.application.dto;

public record OAuthUserInfo(
        String oauthId,
        String email,
        String nickname,
        String profileImageUrl
) {}
