package com.prompthub.admin.user.domain.repository;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserProfile;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
	Optional<User> findById(UUID userId);
	User save(User user);
	List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size);
	long countUsers(UserStatus status, UserRole role, String keyword);
	long countCreatedBetween(LocalDateTime from, LocalDateTime to);
	List<User> findAllByIds(List<UUID> userIds);
	List<UserProfile> findProfilesByIds(List<UUID> userIds);
}
