package com.prompthub.user.admin.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserStatsResult;
import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.admin.application.dto.AdminUserSummaryResult;
import com.prompthub.user.admin.application.dto.ChangeUserStatusCommand;
import com.prompthub.user.admin.application.usecase.AdminUserUseCase;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserStatus;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserApplicationService implements AdminUserUseCase {

    private final UserRepository userRepository;

    @Override
    public AdminUserPageResult listUsers(AdminUserListQuery query) {
        int zeroBasedPage = query.page() - 1;

        List<User> users = userRepository.findUsers(
                query.status(), query.role(), query.keyword(), zeroBasedPage, query.size());
        long total = userRepository.countUsers(
                query.status(), query.role(), query.keyword());

        List<AdminUserSummaryResult> results = users.stream()
                .map(AdminUserSummaryResult::from)
                .toList();

        boolean hasNext = total > (long) query.page() * query.size();

        return new AdminUserPageResult(results, query.page(), query.size(), total, hasNext);
    }

    @Override
    @Transactional
    public AdminUserStatusResult changeUserStatus(ChangeUserStatusCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.AUTH_NOT_FOUND));

        applyStatus(user, command.status());

        userRepository.save(user);
        return AdminUserStatusResult.from(user);
    }

    @Override
    public AdminUserStatsResult getUserStats() {
        long totalUsers = userRepository.countUsers(null, null, null);

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime startOfNextDay = startOfDay.plusDays(1);
        long todayNewUsers = userRepository.countCreatedBetween(startOfDay, startOfNextDay);

        return new AdminUserStatsResult(totalUsers, todayNewUsers);
    }

    private static void applyStatus(User user, UserStatus status) {
        switch (status) {
            case ACTIVE -> user.activate();
            case BLOCKED -> user.block();
            case WITHDRAWN -> user.withdraw();
        }
    }
}
