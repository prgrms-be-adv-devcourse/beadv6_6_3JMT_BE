package com.prompthub.user.admin.presentation.controller;

import com.prompthub.exception.BusinessException;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserStatsResult;
import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.admin.application.dto.ChangeUserStatusCommand;
import com.prompthub.user.admin.application.usecase.AdminUserUseCase;
import com.prompthub.user.admin.presentation.dto.request.ChangeUserStatusRequest;
import com.prompthub.user.admin.presentation.dto.response.AdminUserResponse;
import com.prompthub.user.admin.presentation.dto.response.AdminUserStatsResponse;
import com.prompthub.user.admin.presentation.dto.response.AdminUserStatusResponse;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "관리자 - 회원", description = "관리자 회원 목록 조회 및 상태 관리")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v2/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserUseCase adminUserUseCase;

    @Operation(summary = "전체 사용자 목록 조회", description = "상태·역할·키워드 필터, 페이지네이션 지원. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/users")
    public PageResponse<AdminUserResponse> listUsers(
            @Parameter(description = "계정 상태 필터 (active | suspended | withdrawn | ALL)", example = "ALL")
            @RequestParam(defaultValue = "ALL") String status,
            @Parameter(description = "역할 필터 (buyer | seller | ALL)", example = "ALL")
            @RequestParam(defaultValue = "ALL") String role,
            @Parameter(description = "이름·이메일·회원ID 검색 키워드")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지당 항목 수", example = "20")
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

    @Operation(summary = "회원 통계 조회", description = "누적 회원 수 및 오늘 신규 가입 수 반환. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/stats/users")
    public ApiResult<AdminUserStatsResponse> getUserStats() {
        AdminUserStatsResult result = adminUserUseCase.getUserStats();
        return ApiResult.success(AdminUserStatsResponse.from(result));
    }

    @Operation(summary = "사용자 상태 변경", description = "active | suspended | withdrawn 으로 변경. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "변경 성공")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음 (A001)")
    @PatchMapping("/users/{userId}/status")
    public ApiResult<AdminUserStatusResponse> changeUserStatus(
            @Parameter(description = "대상 사용자 ID") @PathVariable UUID userId,
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
