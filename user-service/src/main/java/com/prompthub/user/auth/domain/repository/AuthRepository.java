package com.prompthub.user.auth.domain.repository;

import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.OAuthProvider;

import java.util.Optional;

public interface AuthRepository {
    Optional<Auth> findByProviderAndOauthId(OAuthProvider provider, String oauthId);
    Auth save(Auth auth);
}
