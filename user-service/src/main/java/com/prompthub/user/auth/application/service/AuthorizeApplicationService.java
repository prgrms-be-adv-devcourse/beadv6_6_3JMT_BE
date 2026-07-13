package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.application.usecase.AuthorizeUseCase;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
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

    @Override
    public AuthorizeResult authorize(UUID userId) {
        return authorizationCacheRepository.find(userId)
                .map(AuthorizeResult::from)
                .orElseGet(() -> loadFromDbAndCache(userId));
    }

    private AuthorizeResult loadFromDbAndCache(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        AuthzSnapshot snapshot = new AuthzSnapshot(user.getStatus(), user.getPrimaryRole());
        authorizationCacheRepository.save(userId, snapshot);
        return AuthorizeResult.from(snapshot);
    }
}
