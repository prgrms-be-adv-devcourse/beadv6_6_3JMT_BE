package com.prompthub.user.auth.domain.repository;

import com.prompthub.user.auth.domain.model.AuthzSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface AuthorizationCacheRepository {
    Optional<AuthzSnapshot> find(UUID userId);
    void save(UUID userId, AuthzSnapshot snapshot);
    void evict(UUID userId);
}
