package com.prompthub.user.admin.presentation.controller;

import com.prompthub.exception.BusinessException;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.admin.application.dto.ChangeUserStatusCommand;
import com.prompthub.user.admin.application.usecase.AdminUserUseCase;
import com.prompthub.user.admin.presentation.dto.request.ChangeUserStatusRequest;
import com.prompthub.user.admin.presentation.dto.response.AdminUserResponse;
import com.prompthub.user.admin.presentation.dto.response.AdminUserStatusResponse;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserUseCase adminUserUseCase;

    @GetMapping("/users")
    public PageResponse<AdminUserResponse> listUsers(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "ALL") String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AdminUserListQuery query = new AdminUserListQuery(
                parseStatus(status), parseRole(role), keyword, page, size);

        AdminUserPageResult result = adminUserUseCase.listUsers(query);

        List<AdminUserResponse> responseData = result.users().stream()
                .map(AdminUserResponse::from)
                .toList();

        return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResult<AdminUserStatusResponse> changeUserStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeUserStatusRequest request
    ) {
        UserStatus targetStatus = parseStatus(request.status());
        ChangeUserStatusCommand command = new ChangeUserStatusCommand(userId, targetStatus);

        AdminUserStatusResult result = adminUserUseCase.changeUserStatus(command);
        return ApiResult.success(AdminUserStatusResponse.from(result));
    }

    private static UserStatus parseStatus(String statusParam) {
        return switch (statusParam) {
            case "active" -> UserStatus.ACTIVE;
            case "suspended" -> UserStatus.BLOCKED;
            case "withdrawn" -> UserStatus.WITHDRAWN;
            case "ALL" -> null;
            default -> throw new BusinessException(UserErrorCode.VALIDATION_FAILED);
        };
    }

    private static UserRole parseRole(String roleParam) {
        return switch (roleParam) {
            case "buyer" -> UserRole.BUYER;
            case "seller" -> UserRole.SELLER;
            case "ALL" -> null;
            default -> throw new BusinessException(UserErrorCode.VALIDATION_FAILED);
        };
    }
}
