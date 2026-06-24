package com.prompthub.user.user.domain.repository;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID userId);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    User save(User user);
    List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size);
    long countUsers(UserStatus status, UserRole role, String keyword);
    long countCreatedBetween(LocalDateTime from, LocalDateTime to);
}
