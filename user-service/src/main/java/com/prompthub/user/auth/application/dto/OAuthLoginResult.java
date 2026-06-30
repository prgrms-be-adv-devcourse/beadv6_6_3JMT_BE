package com.prompthub.user.auth.application.dto;

import com.prompthub.user.user.domain.model.UserRole;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record OAuthLoginResult(
        UUID userId,
        String name,
        String email,
        Set<UserRole> roles,
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        boolean isNewUser
) {}
