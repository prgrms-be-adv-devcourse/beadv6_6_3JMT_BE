package com.prompthub.user.user.domain.repository;

import com.prompthub.user.user.domain.model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID userId);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    User save(User user);
}
