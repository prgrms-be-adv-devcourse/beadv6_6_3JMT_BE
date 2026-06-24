package com.prompthub.user.auth.application.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.AuthUseCase;
import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.OAuthEmailAlreadyUsedException;
import com.prompthub.user.auth.domain.exception.OrphanedAuthRecordException;
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
                .findByProviderAndOauthId(command.provider(), command.oauthId());

        final User user;
        final boolean isNewUser;

        if (existingAuth.isPresent()) {
            user = userRepository.findById(existingAuth.get().getUserId())
                    .orElseThrow(() -> new OrphanedAuthRecordException(
                            existingAuth.get().getUserId().toString()));
            isNewUser = false;
        } else {
            if (userRepository.existsByEmail(command.email())) {
                throw new OAuthEmailAlreadyUsedException();
            }

            User newUser = User.create(
                    command.name(),
                    command.email(),
                    command.profileImage(),
                    UserRole.BUYER,
                    true
            );
            userRepository.save(newUser);
            authRepository.save(Auth.create(newUser.getUserId(), command.provider(), command.oauthId()));
            user = newUser;
            isNewUser = true;
        }

        JwtTokenProvider.TokenResult accessTokenResult = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getPrimaryRole());
        JwtTokenProvider.TokenResult refreshTokenResult = jwtTokenProvider.generateRefreshToken(user.getUserId());

        refreshTokenRepository.deleteByUserId(user.getUserId());
        refreshTokenRepository.save(
                RefreshToken.create(user.getUserId(), refreshTokenResult.token(), refreshTokenResult.expiresAt())
        );

        return new OAuthLoginResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getPrimaryRole(),
                accessTokenResult.token(),
                refreshTokenResult.token(),
                "Bearer",
                accessTokenResult.expiresAt(),
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

        JwtTokenProvider.TokenResult result = jwtTokenProvider.generateAccessToken(userId, user.getPrimaryRole());
        return new TokenRefreshResult(result.token(), result.expiresAt());
    }

    @Override
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }
}
