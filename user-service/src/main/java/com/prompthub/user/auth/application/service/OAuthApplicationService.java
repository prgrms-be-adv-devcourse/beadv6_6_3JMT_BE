package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.usecase.OAuthUseCase;
import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class OAuthApplicationService implements OAuthUseCase {

    private final AuthRepository authRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public OAuthLoginResult login(OAuthLoginCommand command) {
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
            Optional<User> existingUser = userRepository.findByEmail(command.email());

            if (existingUser.isPresent()) {
                user = existingUser.get();
                authRepository.save(Auth.create(user.getUserId(), command.provider(), command.providerUserId()));
                isNewUser = false;
            } else {
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
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUserId());

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
}
