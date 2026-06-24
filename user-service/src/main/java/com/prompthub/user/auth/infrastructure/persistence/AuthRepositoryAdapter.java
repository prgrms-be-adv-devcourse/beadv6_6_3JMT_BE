package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import com.prompthub.user.auth.domain.repository.AuthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthRepositoryAdapter implements AuthRepository {

    private final AuthJpaRepository authJpaRepository;

    @Override
    public Optional<Auth> findByProviderAndOauthId(OAuthProvider provider, String oauthId) {
        return authJpaRepository.findByProviderAndOauthId(provider, oauthId);
    }

    @Override
    public Auth save(Auth auth) {
        return authJpaRepository.save(auth);
    }
}
