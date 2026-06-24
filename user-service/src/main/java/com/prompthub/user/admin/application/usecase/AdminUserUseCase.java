package com.prompthub.user.admin.application.usecase;

import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;

public interface AdminUserUseCase {
    AdminUserPageResult listUsers(AdminUserListQuery query);
}
