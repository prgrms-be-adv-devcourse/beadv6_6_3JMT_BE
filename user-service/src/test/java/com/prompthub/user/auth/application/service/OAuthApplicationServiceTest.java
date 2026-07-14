package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.client.KakaoUserInfoClient;
import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.OAuthUserInfo;
import com.prompthub.user.auth.domain.exception.OAuthEmailAlreadyUsedException;
import com.prompthub.user.auth.domain.exception.OAuthVerificationFailedException;
import com.prompthub.user.auth.domain.exception.OrphanedAuthRecordException;
import com.prompthub.user.auth.domain.exception.UnsupportedOAuthProviderException;
import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
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
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OAuthApplicationServiceTest {

    @Mock
    private AuthRepository authRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private KakaoUserInfoClient kakaoUserInfoClient;

    @InjectMocks
    private AuthApplicationService authApplicationService;

    private static final Instant ACCESS_EXPIRES_AT = Instant.now().plusSeconds(3600);
    private static final Instant REFRESH_EXPIRES_AT = Instant.now().plusSeconds(2592000);

    private static final String ACCESS_TOKEN = "kakao-access-token";

    private static final OAuthLoginCommand COMMAND = new OAuthLoginCommand(
            OAuthProvider.KAKAO,
            ACCESS_TOKEN
    );

    private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(
            "kakao_123456",
            "test@kakao.com",
            "테스트유저",
            "https://profile.kakao.com/img.jpg"
    );

    @Test
    void login_기존_연동_존재_시_기존_유저_반환() {
        User existingUser = User.create("테스트유저", "test@kakao.com", null, UserRole.BUYER, true);
        Auth existingAuth = Auth.create(existingUser.getUserId(), OAuthProvider.KAKAO, "kakao_123456");

        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN)).willReturn(USER_INFO);
        given(authRepository.findByProviderAndOauthId(OAuthProvider.KAKAO, "kakao_123456"))
                .willReturn(Optional.of(existingAuth));
        given(userRepository.findById(existingUser.getUserId()))
                .willReturn(Optional.of(existingUser));
        given(jwtTokenProvider.generateAccessToken(any(UUID.class), eq(0L)))
                .willReturn(new JwtTokenProvider.TokenResult("access-token", ACCESS_EXPIRES_AT));
        given(jwtTokenProvider.generateRefreshToken(any()))
                .willReturn(new JwtTokenProvider.TokenResult("refresh-token", REFRESH_EXPIRES_AT));
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));

        OAuthLoginResult result = authApplicationService.oAuthLogin(COMMAND);

        assertThat(result.isNewUser()).isFalse();
        assertThat(result.email()).isEqualTo("test@kakao.com");
        assertThat(result.accessToken()).isEqualTo("access-token");
        then(userRepository).should(never()).save(any());
        then(authRepository).should(never()).save(any());
    }

    @Test
    void login_이메일_이미_존재하면_OAuthEmailAlreadyUsedException() {
        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN)).willReturn(USER_INFO);
        given(authRepository.findByProviderAndOauthId(OAuthProvider.KAKAO, "kakao_123456"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("test@kakao.com"))
                .willReturn(true);

        assertThatThrownBy(() -> authApplicationService.oAuthLogin(COMMAND))
                .isInstanceOf(OAuthEmailAlreadyUsedException.class);

        then(userRepository).should(never()).save(any());
        then(authRepository).should(never()).save(any());
    }

    @Test
    void login_신규_사용자_자동_회원가입() {
        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN)).willReturn(USER_INFO);
        given(authRepository.findByProviderAndOauthId(OAuthProvider.KAKAO, "kakao_123456"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("test@kakao.com")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(authRepository.save(any(Auth.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.generateAccessToken(any(UUID.class), eq(0L)))
                .willReturn(new JwtTokenProvider.TokenResult("access-token", ACCESS_EXPIRES_AT));
        given(jwtTokenProvider.generateRefreshToken(any()))
                .willReturn(new JwtTokenProvider.TokenResult("refresh-token", REFRESH_EXPIRES_AT));
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));

        OAuthLoginResult result = authApplicationService.oAuthLogin(COMMAND);

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.email()).isEqualTo("test@kakao.com");
        assertThat(result.roles()).containsExactly(UserRole.BUYER);
        then(userRepository).should().save(any(User.class));
        then(authRepository).should().save(any(Auth.class));
    }

    @Test
    void login_신규_사용자_tokenType_Bearer() {
        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN)).willReturn(USER_INFO);
        given(authRepository.findByProviderAndOauthId(OAuthProvider.KAKAO, "kakao_123456"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("test@kakao.com")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(authRepository.save(any(Auth.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.generateAccessToken(any(UUID.class), eq(0L)))
                .willReturn(new JwtTokenProvider.TokenResult("access-token", ACCESS_EXPIRES_AT));
        given(jwtTokenProvider.generateRefreshToken(any()))
                .willReturn(new JwtTokenProvider.TokenResult("refresh-token", REFRESH_EXPIRES_AT));
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));

        OAuthLoginResult result = authApplicationService.oAuthLogin(COMMAND);

        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void login_auth_존재하지만_user_없으면_OrphanedAuthRecordException() {
        UUID missingUserId = UUID.randomUUID();
        Auth orphanAuth = Auth.create(missingUserId, OAuthProvider.KAKAO, "kakao_123456");

        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN)).willReturn(USER_INFO);
        given(authRepository.findByProviderAndOauthId(OAuthProvider.KAKAO, "kakao_123456"))
                .willReturn(Optional.of(orphanAuth));
        given(userRepository.findById(missingUserId))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authApplicationService.oAuthLogin(COMMAND))
                .isInstanceOf(OrphanedAuthRecordException.class)
                .hasMessageContaining(missingUserId.toString());
    }

    @Test
    void login_카카오_인증_실패시_예외_전파() {
        given(kakaoUserInfoClient.fetchUserInfo(ACCESS_TOKEN))
                .willThrow(new OAuthVerificationFailedException("유효하지 않은 액세스 토큰"));

        assertThatThrownBy(() -> authApplicationService.oAuthLogin(COMMAND))
                .isInstanceOf(OAuthVerificationFailedException.class);

        then(authRepository).should(never()).findByProviderAndOauthId(any(), any());
        then(userRepository).should(never()).save(any());
    }

    @Test
    void login_카카오가_아닌_provider면_UnsupportedOAuthProviderException() {
        OAuthLoginCommand naverCommand = new OAuthLoginCommand(OAuthProvider.NAVER, ACCESS_TOKEN);

        assertThatThrownBy(() -> authApplicationService.oAuthLogin(naverCommand))
                .isInstanceOf(UnsupportedOAuthProviderException.class);

        then(kakaoUserInfoClient).should(never()).fetchUserInfo(any());
    }
}
