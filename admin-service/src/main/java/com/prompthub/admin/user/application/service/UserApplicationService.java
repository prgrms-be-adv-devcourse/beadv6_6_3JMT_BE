package com.prompthub.admin.user.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserRoleCommand;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserRoleResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserProfile;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
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
public class UserApplicationService implements UserUseCase {

	private final UserRepository userRepository;
	private final AuthorizationCacheRepository authorizationCacheRepository;
	private final SessionRevocationUseCase sessionRevocationUseCase;

	@Override
	public UserPageResult listUsers(UserListQuery query) {
		int zeroBasedPage = query.page() - 1;

		List<User> users = userRepository.findUsers(
			query.status(), query.role(), query.keyword(), zeroBasedPage, query.size());
		long total = userRepository.countUsers(query.status(), query.role(), query.keyword());

		List<UserSummaryResult> results = users.stream()
			.map(UserSummaryResult::from)
			.toList();

		boolean hasNext = total > (long) query.page() * query.size();

		return new UserPageResult(results, query.page(), query.size(), total, hasNext);
	}

	@Override
	@Transactional
	public UserStatusResult changeUserStatus(ChangeUserStatusCommand command) {
		User user = userRepository.findById(command.userId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));

		applyStatus(user, command.status());

		userRepository.save(user);
		if (command.status() == UserStatus.WITHDRAWN) {
			sessionRevocationUseCase.revoke(user.getUserId());
		} else {
			authorizationCacheRepository.evict(user.getUserId());
		}
		return UserStatusResult.from(user);
	}

	@Override
	@Transactional
	public UserRoleResult changeUserRole(ChangeUserRoleCommand command) {
		User user = userRepository.findById(command.userId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));

		user.changeRole(command.role());

		userRepository.save(user);
		authorizationCacheRepository.evict(user.getUserId());
		return UserRoleResult.from(user);
	}

	@Override
	public UserStatsResult getUserStats() {
		long totalUsers = userRepository.countUsers(null, null, null);

		LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
		LocalDateTime startOfNextDay = startOfDay.plusDays(1);
		long todayNewUsers = userRepository.countCreatedBetween(startOfDay, startOfNextDay);

		return new UserStatsResult(totalUsers, todayNewUsers);
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
