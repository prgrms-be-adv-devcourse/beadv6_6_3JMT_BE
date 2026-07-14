package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.application.usecase.AuthorizeUseCase;
import com.prompthub.user.auth.domain.exception.SessionInvalidatedException;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
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
public class AuthorizeApplicationService implements AuthorizeUseCase {

    private final AuthorizationCacheRepository authorizationCacheRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    public AuthorizeResult authorize(UUID userId, Long epoch) {
        validateEpoch(userId, epoch);
        return authorizationCacheRepository.find(userId)
                .map(AuthorizeResult::from)
                .orElseGet(() -> loadFromDbAndCache(userId));
    }

    // epoch 불일치·부재 = 로그아웃/재로그인으로 회전된 이전 세션 → fail-closed(ADR-0008 결정 8-1)
    private void validateEpoch(UUID userId, Long epoch) {
        if (epoch == null) {
            throw new SessionInvalidatedException();
        }
        RefreshToken refreshToken = refreshTokenRepository.findByUserId(userId)
                .orElseThrow(SessionInvalidatedException::new);
        if (refreshToken.getEpoch() != epoch) {
            throw new SessionInvalidatedException();
        }
    }

    private AuthorizeResult loadFromDbAndCache(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        AuthzSnapshot snapshot = new AuthzSnapshot(user.getStatus(), user.getPrimaryRole());
        authorizationCacheRepository.save(userId, snapshot);
        return AuthorizeResult.from(snapshot);
    }
}
