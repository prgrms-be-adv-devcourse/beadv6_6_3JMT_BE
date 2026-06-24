package com.prompthub.user.admin.application.usecase;

import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.admin.application.dto.ChangeUserStatusCommand;

public interface AdminUserUseCase {
    AdminUserPageResult listUsers(AdminUserListQuery query);
    AdminUserStatusResult changeUserStatus(ChangeUserStatusCommand command);
}
