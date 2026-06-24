package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.Auth;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuthJpaRepository extends JpaRepository<Auth, UUID> {
    Optional<Auth> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
