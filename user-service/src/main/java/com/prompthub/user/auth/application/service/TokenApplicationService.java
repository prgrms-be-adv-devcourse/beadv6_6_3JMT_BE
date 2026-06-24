package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.TokenRefreshUseCase;
import com.prompthub.user.auth.infrastructure.jwt.JwtTokenProvider;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenApplicationService implements TokenRefreshUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Override
    public TokenRefreshResult refresh(TokenRefreshCommand command) {
        UUID userId = jwtTokenProvider.parseRefreshToken(command.refreshToken());
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        String accessToken = jwtTokenProvider.generateAccessToken(userId, user.getRole());
        return new TokenRefreshResult(accessToken, jwtTokenProvider.getAccessTokenExpiresAt());
    }
}
