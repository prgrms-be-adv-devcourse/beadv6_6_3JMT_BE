package com.prompthub.user.auth.application.dto;

import com.prompthub.user.auth.domain.model.OAuthProvider;

public record OAuthLoginCommand(
        OAuthProvider provider,
        String oauthId,
        String nickname,
        String profileImage,
        String email
) {}
