package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.RefreshTokenReuseDetectedException;
import com.prompthub.user.auth.domain.exception.TokenExpiredException;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class TokenApplicationServiceTest {

    @Mock
    private AuthRepository authRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthApplicationService authApplicationService;

    private static final String REFRESH_TOKEN = "valid-refresh-token";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant EXPIRES_AT = Instant.now().plusSeconds(3600);

    private RefreshToken storedToken(long epoch) {
        RefreshToken refreshToken = RefreshToken.create(USER_ID, REFRESH_TOKEN, Instant.now().plusSeconds(604800));
        for (long i = 0; i < epoch; i++) {
            refreshToken.rotate(REFRESH_TOKEN, Instant.now().plusSeconds(604800));
        }
        return refreshToken;
    }

    @Test
    void refresh_정상_새_AT와_새_RT_반환_RTR() {
        User user = User.create("테스트유저", "test@example.com", null, UserRole.BUYER, true);
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(storedToken(0)));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(jwtTokenProvider.generateRefreshToken(USER_ID))
                .willReturn(new JwtTokenProvider.TokenResult("new-refresh-token", Instant.now().plusSeconds(604800)));
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.generateAccessToken(eq(USER_ID), eq(1L)))
                .willReturn(new JwtTokenProvider.TokenResult("new-access-token", EXPIRES_AT));

        TokenRefreshResult result = authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN));

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void refresh_RT_회전시_epoch_1증가() {
        User user = User.create("테스트유저", "test@example.com", null, UserRole.BUYER, true);
        RefreshToken stored = storedToken(3);
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(stored));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(jwtTokenProvider.generateRefreshToken(USER_ID))
                .willReturn(new JwtTokenProvider.TokenResult("new-refresh-token", Instant.now().plusSeconds(604800)));
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.generateAccessToken(eq(USER_ID), eq(4L)))
                .willReturn(new JwtTokenProvider.TokenResult("new-access-token", EXPIRES_AT));

        authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN));

        then(jwtTokenProvider).should().generateAccessToken(USER_ID, 4L);
    }

    @Test
    void refresh_제시된_RT가_저장된_RT와_다르면_재사용감지_전체세션무효화() {
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
        RefreshToken rotatedAway = RefreshToken.create(USER_ID, "rotated-away-token", Instant.now().plusSeconds(604800));
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(rotatedAway));

        assertThatThrownBy(() -> authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN)))
                .isInstanceOf(RefreshTokenReuseDetectedException.class);

        then(refreshTokenRepository).should().deleteByUserId(USER_ID);
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void refresh_만료된_RT_TokenExpiredException_전파() {
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willThrow(new TokenExpiredException());

        assertThatThrownBy(() -> authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN)))
                .isInstanceOf(TokenExpiredException.class);

        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void refresh_유효하지_않은_RT_InvalidRefreshTokenException_전파() {
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willThrow(new InvalidRefreshTokenException());

        assertThatThrownBy(() -> authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void refresh_DB에_없는_RT_InvalidRefreshTokenException() {
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void refresh_userId_존재하지_않으면_UserNotFoundException() {
        given(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(storedToken(0)));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authApplicationService.refresh(new TokenRefreshCommand(REFRESH_TOKEN)))
                .isInstanceOf(UserNotFoundException.class);
    }
}
