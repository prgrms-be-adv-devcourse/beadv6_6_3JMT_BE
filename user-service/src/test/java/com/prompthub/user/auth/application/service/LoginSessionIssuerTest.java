package com.prompthub.user.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.application.dto.OAuthLoginStatus;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

@ExtendWith(MockitoExtension.class)
class LoginSessionIssuerTest {

    private static final Instant ACCESS_EXPIRES_AT = Instant.parse("2026-07-30T12:15:00Z");
    private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private AuthorizationCacheRepository authorizationCacheRepository;

    @InjectMocks
    private LoginSessionIssuer loginSessionIssuer;

    @Test
    void issue_기존_RT를_교체하고_AT_RT와_현재_인가정보를_반환한다() {
        User user = User.create("사용자", "user@example.com", null, UserRole.BUYER, true);
        given(jwtTokenProvider.generateRefreshToken(user.getUserId()))
                .willReturn(new JwtTokenProvider.TokenResult("refresh-token", REFRESH_EXPIRES_AT));
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(jwtTokenProvider.generateAccessToken(user.getUserId(), 0L))
                .willReturn(new JwtTokenProvider.TokenResult("access-token", ACCESS_EXPIRES_AT));

        OAuthLoginCompletedResult result = loginSessionIssuer.issue(user, false);

        assertThat(result.loginStatus()).isEqualTo(OAuthLoginStatus.COMPLETED);
        assertThat(result.isNewUser()).isFalse();
        assertThat(result.userId()).isEqualTo(user.getUserId());
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        then(refreshTokenRepository).should().deleteByUserId(user.getUserId());
        then(authorizationCacheRepository).should().save(
                user.getUserId(),
                new AuthzSnapshot(UserStatus.ACTIVE, UserRole.BUYER));
    }
}
