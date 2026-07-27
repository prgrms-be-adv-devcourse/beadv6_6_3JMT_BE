package com.prompthub.admin.user.service;

import com.prompthub.admin.auth.service.AuthService;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.dto.ChangeUserRoleCommand;
import com.prompthub.admin.user.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.dto.UserListQuery;
import com.prompthub.admin.user.dto.UserPageResult;
import com.prompthub.admin.user.dto.UserRoleResult;
import com.prompthub.admin.user.dto.UserStatsResult;
import com.prompthub.admin.user.dto.UserStatusResult;
import com.prompthub.admin.user.dto.UserProfile;
import com.prompthub.admin.user.dto.UserSummaryResult;
import com.prompthub.admin.user.entity.User;
import com.prompthub.admin.user.entity.enums.UserStatus;
import com.prompthub.admin.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository userRepository;
	private final AuthService authService;

	public UserPageResult listUsers(UserListQuery query) {
		List<User> users = userRepository.findUsers(
			query.status(), query.role(), query.keyword(), query.page(), query.size());
		long total = userRepository.countUsers(query.status(), query.role(), query.keyword());

		List<UserSummaryResult> results = users.stream()
			.map(UserSummaryResult::from)
			.toList();

		boolean hasNext = total > (long) (query.page() + 1) * query.size();

		return new UserPageResult(results, query.page(), query.size(), total, hasNext);
	}

	@Transactional
	public UserStatusResult changeUserStatus(ChangeUserStatusCommand command) {
		User user = userRepository.findById(command.userId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));

		applyStatus(user, command.status());

		userRepository.save(user);
		if (command.status() == UserStatus.WITHDRAWN) {
			authService.revoke(user.getUserId());
		} else {
			authService.evictAuthorizationCache(user.getUserId());
		}
		return UserStatusResult.from(user);
	}

	@Transactional
	public UserRoleResult changeUserRole(ChangeUserRoleCommand command) {
		User user = userRepository.findById(command.userId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));

		user.changeRole(command.role());

		userRepository.save(user);
		authService.evictAuthorizationCache(user.getUserId());
		return UserRoleResult.from(user);
	}

	public UserStatsResult getUserStats() {
		long totalUsers = userRepository.countUsers(null, null, null);

		LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
		LocalDateTime startOfNextDay = startOfDay.plusDays(1);
		long todayNewUsers = userRepository.countCreatedBetween(startOfDay, startOfNextDay);

		return new UserStatsResult(totalUsers, todayNewUsers);
	}

	public List<UUID> findIdsByNameContainingIgnoreCase(String keyword) {
		return userRepository.findByNameContainingIgnoreCase(keyword).stream()
			.map(User::getUserId)
			.toList();
	}

	public Map<UUID, String> findNamesByIds(List<UUID> userIds) {
		List<UUID> distinctIds = userIds.stream().distinct().toList();
		if (distinctIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllByIds(distinctIds).stream()
			.collect(Collectors.toUnmodifiableMap(User::getUserId, User::getName));
	}

	public Map<UUID, UserProfile> findProfilesByIds(List<UUID> userIds) {
		List<UUID> distinctIds = userIds.stream().distinct().toList();
		if (distinctIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findProfilesByIds(distinctIds).stream()
			.collect(Collectors.toUnmodifiableMap(UserProfile::userId, Function.identity()));
	}

	private static void applyStatus(User user, UserStatus status) {
		switch (status) {
			case ACTIVE -> user.activate();
			case BLOCKED -> user.block();
			case WITHDRAWN -> user.withdraw();
		}
	}
}
