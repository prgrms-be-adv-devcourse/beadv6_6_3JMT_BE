package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.AuthUseCase;
import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.OAuthEmailAlreadyUsedException;
import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthApplicationService implements AuthUseCase {

    private final AuthRepository authRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public OAuthLoginResult oAuthLogin(OAuthLoginCommand command) {
        Optional<Auth> existingAuth = authRepository
                .findByProviderAndProviderUserId(command.provider(), command.providerUserId());

        final User user;
        final boolean isNewUser;

        if (existingAuth.isPresent()) {
            user = userRepository.findById(existingAuth.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "auth 레코드에 연결된 user를 찾을 수 없습니다. userId=" + existingAuth.get().getUserId()));
            isNewUser = false;
        } else {
            if (userRepository.existsByEmail(command.email())) {
                throw new OAuthEmailAlreadyUsedException();
            }

            User newUser = User.create(
                    command.nickname(),
                    command.email(),
                    command.profileImage(),
                    UserRole.BUYER,
                    true
            );
            userRepository.save(newUser);
            authRepository.save(Auth.create(newUser.getUserId(), command.provider(), command.providerUserId()));
            user = newUser;
            isNewUser = true;
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUserId());

        refreshTokenRepository.save(
                RefreshToken.create(user.getUserId(), refreshToken, jwtTokenProvider.getRefreshTokenExpiresAt())
        );

        return new OAuthLoginResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                accessToken,
                refreshToken,
                "Bearer",
                jwtTokenProvider.getAccessTokenExpiresAt(),
                isNewUser
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TokenRefreshResult refresh(TokenRefreshCommand command) {
        UUID userId = jwtTokenProvider.parseRefreshToken(command.refreshToken());

        refreshTokenRepository.findByToken(command.refreshToken())
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        String accessToken = jwtTokenProvider.generateAccessToken(userId, user.getRole());
        return new TokenRefreshResult(accessToken, jwtTokenProvider.getAccessTokenExpiresAt());
    }

    @Override
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }
}
