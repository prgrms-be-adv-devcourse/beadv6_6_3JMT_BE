package com.prompthub.admin.user.application.usecase;

import com.prompthub.admin.user.application.dto.ChangeUserRoleCommand;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserRoleResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;

public interface UserUseCase {
	UserPageResult listUsers(UserListQuery query);
	UserStatusResult changeUserStatus(ChangeUserStatusCommand command);
	UserRoleResult changeUserRole(ChangeUserRoleCommand command);
	UserStatsResult getUserStats();
}
