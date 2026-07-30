package com.prompthub.user.auth.application.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prompthub.user.auth.application.client.KakaoUserInfoClient;
import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.OAuthRejoinRequiredResult;
import com.prompthub.user.auth.application.dto.OAuthUserInfo;
import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.AuthUseCase;
import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.BlockedAccountException;
import com.prompthub.user.auth.domain.exception.OAuthEmailAlreadyUsedException;
import com.prompthub.user.auth.domain.exception.OrphanedAuthRecordException;
import com.prompthub.user.auth.domain.exception.RefreshTokenReuseDetectedException;
import com.prompthub.user.auth.domain.exception.UnsupportedOAuthProviderException;
import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.model.RejoinToken;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.domain.repository.RejoinTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthApplicationService implements AuthUseCase {

    private final AuthRepository authRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final KakaoUserInfoClient kakaoUserInfoClient;
    private final LoginSessionIssuer loginSessionIssuer;
    private final RejoinTokenRepository rejoinTokenRepository;

    @Override
    public OAuthLoginResult oAuthLogin(OAuthLoginCommand command) {
        if (command.provider() != OAuthProvider.KAKAO) {
            throw new UnsupportedOAuthProviderException(command.provider().name());
        }

        OAuthUserInfo userInfo = kakaoUserInfoClient.fetchUserInfo(command.accessToken());

        Optional<Auth> existingAuth = authRepository
                .findByProviderAndOauthId(command.provider(), userInfo.oauthId());

        if (existingAuth.isPresent()) {
            User user = userRepository.findById(existingAuth.get().getUserId())
                    .orElseThrow(() -> new OrphanedAuthRecordException(
                            existingAuth.get().getUserId().toString()));
            return switch (user.getStatus()) {
                case ACTIVE -> loginSessionIssuer.issue(user, false);
                case WITHDRAWN -> createRejoinRequiredResult(user);
                case BLOCKED -> throw new BlockedAccountException();
            };
        }

        if (userRepository.existsByEmail(userInfo.email())) {
            throw new OAuthEmailAlreadyUsedException();
        }

        User newUser = User.create(
                userInfo.nickname(),
                userInfo.email(),
                userInfo.profileImageUrl(),
                UserRole.BUYER,
                true
        );
        userRepository.save(newUser);
        authRepository.save(Auth.create(newUser.getUserId(), command.provider(), userInfo.oauthId()));
        return loginSessionIssuer.issue(newUser, true);
    }

    @Override
    public TokenRefreshResult refresh(TokenRefreshCommand command) {
        UUID userId = jwtTokenProvider.parseRefreshToken(command.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByUserIdForUpdate(userId)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!stored.getToken().equals(command.refreshToken())) {
            refreshTokenRepository.deleteByUserId(userId);
            throw new RefreshTokenReuseDetectedException();
        }

        userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        JwtTokenProvider.TokenResult newRefreshTokenResult = jwtTokenProvider.generateRefreshToken(userId);
        stored.rotate(newRefreshTokenResult.token(), newRefreshTokenResult.expiresAt());
        RefreshToken rotated = refreshTokenRepository.save(stored);

        JwtTokenProvider.TokenResult newAccessTokenResult =
                jwtTokenProvider.generateAccessToken(userId, rotated.getEpoch());

        return new TokenRefreshResult(
                newAccessTokenResult.token(),
                newAccessTokenResult.expiresAt(),
                newRefreshTokenResult.token()
        );
    }

    @Override
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private OAuthRejoinRequiredResult createRejoinRequiredResult(User user) {
        RejoinToken rejoinToken = rejoinTokenRepository.create(user.getUserId());
        return new OAuthRejoinRequiredResult(rejoinToken.value(), rejoinToken.expiresAt());
    }
}
