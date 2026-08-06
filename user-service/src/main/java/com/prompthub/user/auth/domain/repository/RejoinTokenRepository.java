package com.prompthub.user.auth.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.prompthub.user.auth.domain.model.RejoinToken;

public interface RejoinTokenRepository {

    RejoinToken create(UUID userId);

    Optional<UUID> consume(String rawToken);
}
