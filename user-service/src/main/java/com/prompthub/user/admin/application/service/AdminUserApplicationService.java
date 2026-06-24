package com.prompthub.user.admin.application.service;

import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserSummaryResult;
import com.prompthub.user.admin.application.usecase.AdminUserUseCase;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
