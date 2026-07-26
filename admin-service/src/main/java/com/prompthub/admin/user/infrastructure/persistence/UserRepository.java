package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserProfile;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepository {

	private final UserJpaRepository userJpaRepository;

	public Optional<User> findById(UUID userId) {
		return userJpaRepository.findById(userId);
	}

	public User save(User user) {
		return userJpaRepository.save(user);
	}

	public List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.findAll(spec, PageRequest.of(page, size)).getContent();
	}

	public long countUsers(UserStatus status, UserRole role, String keyword) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.count(spec);
	}

	public long countCreatedBetween(LocalDateTime from, LocalDateTime to) {
		return userJpaRepository.countByCreatedAtBetween(from, to);
	}

	public List<User> findAllByIds(List<UUID> userIds) {
		return userJpaRepository.findAllById(userIds);
	}

	public List<UserProfile> findProfilesByIds(List<UUID> userIds) {
		List<UUID> distinctIds = userIds.stream().distinct().toList();
		if (distinctIds.isEmpty()) {
			return List.of();
		}
		return userJpaRepository.findProfilesByIds(distinctIds).stream()
			.map(profile -> new UserProfile(
				profile.getUserId(), profile.getName(), profile.getProfileImageUrl()))
			.toList();
	}

	public List<User> findByNameContainingIgnoreCase(String name) {
		return userJpaRepository.findByNameContainingIgnoreCase(name);
	}

	private Specification<User> buildSpec(UserStatus status, UserRole role, String keyword) {
		return UserSpecifications.withStatus(status)
			.and(UserSpecifications.withRole(role))
			.and(UserSpecifications.withKeyword(keyword));
	}
}
