package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

	private final UserJpaRepository userJpaRepository;

	@Override
	public Optional<User> findById(UUID userId) {
		return userJpaRepository.findById(userId);
	}

	@Override
	public User save(User user) {
		return userJpaRepository.save(user);
	}

	@Override
	public List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.findAll(spec, PageRequest.of(page, size)).getContent();
	}

	@Override
	public long countUsers(UserStatus status, UserRole role, String keyword) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.count(spec);
	}

	@Override
	public long countCreatedBetween(LocalDateTime from, LocalDateTime to) {
		return userJpaRepository.countByCreatedAtBetween(from, to);
	}

	@Override
	public List<User> findAllByIds(List<UUID> userIds) {
		return userJpaRepository.findAllById(userIds);
	}

	private Specification<User> buildSpec(UserStatus status, UserRole role, String keyword) {
		return UserSpecifications.withStatus(status)
			.and(UserSpecifications.withRole(role))
			.and(UserSpecifications.withKeyword(keyword));
	}
}
