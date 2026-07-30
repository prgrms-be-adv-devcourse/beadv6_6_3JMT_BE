package com.prompthub.user.auth.application.service;

import org.springframework.stereotype.Component;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.model.User;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LoginSessionIssuer {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthorizationCacheRepository authorizationCacheRepository;

    public OAuthLoginCompletedResult issue(User user, boolean isNewUser) {
        JwtTokenProvider.TokenResult refreshTokenResult =
                jwtTokenProvider.generateRefreshToken(user.getUserId());
        refreshTokenRepository.deleteByUserId(user.getUserId());
        RefreshToken savedRefreshToken = refreshTokenRepository.save(
                RefreshToken.create(
                        user.getUserId(),
                        refreshTokenResult.token(),
                        refreshTokenResult.expiresAt())
        );

        JwtTokenProvider.TokenResult accessTokenResult =
                jwtTokenProvider.generateAccessToken(user.getUserId(), savedRefreshToken.getEpoch());

        authorizationCacheRepository.save(
                user.getUserId(),
                new AuthzSnapshot(user.getStatus(), user.getPrimaryRole()));

        return new OAuthLoginCompletedResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getRoles(),
                accessTokenResult.token(),
                refreshTokenResult.token(),
                "Bearer",
                accessTokenResult.expiresAt(),
                isNewUser
        );
    }
}
