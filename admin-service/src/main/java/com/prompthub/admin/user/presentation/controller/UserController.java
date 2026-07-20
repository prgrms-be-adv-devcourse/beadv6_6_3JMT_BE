package com.prompthub.admin.user.presentation.controller;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.presentation.dto.request.ChangeUserStatusRequest;
import com.prompthub.admin.user.presentation.dto.response.UserResponse;
import com.prompthub.admin.user.presentation.dto.response.UserStatsResponse;
import com.prompthub.admin.user.presentation.dto.response.UserStatusResponse;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

@RestController
@RequestMapping("${api.init}/admin")
@RequiredArgsConstructor
@Tag(name = "Admin User", description = "관리자 회원 관리 API (user-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class UserController {

	private final UserUseCase userUseCase;

	@GetMapping("/users")
	@Operation(summary = "전체 사용자 목록 조회", description = "상태·역할·키워드 필터, 페이지네이션 지원. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public PageResponse<UserResponse> listUsers(
		@Parameter(description = "계정 상태 필터 (active | suspended | withdrawn | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String status,
		@Parameter(description = "역할 필터 (buyer | seller | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String role,
		@Parameter(description = "이름·이메일 검색 키워드")
		@RequestParam(required = false) String keyword,
		@Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
		@RequestParam(defaultValue = "1") int page,
		@Parameter(description = "페이지당 항목 수", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		UserListQuery query = new UserListQuery(parseStatusFilter(status), parseRoleFilter(role), keyword, page, size);
		UserPageResult result = userUseCase.listUsers(query);

		List<UserResponse> responseData = result.users().stream()
			.map(UserResponse::from)
			.toList();

		return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
	}

	@GetMapping("/stats/users")
	@Operation(summary = "회원 통계 조회", description = "누적 회원 수 및 오늘 신규 가입 수 반환. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<UserStatsResponse> getUserStats() {
		UserStatsResult result = userUseCase.getUserStats();
		return ApiResult.success(UserStatsResponse.from(result));
	}

	@PatchMapping("/users/{userId}/status")
	@Operation(summary = "사용자 상태 변경", description = "active | suspended | withdrawn 으로 변경. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "변경 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<UserStatusResponse> changeUserStatus(
		@Parameter(description = "대상 사용자 ID") @PathVariable UUID userId,
		@Valid @RequestBody ChangeUserStatusRequest request
	) {
		UserStatus targetStatus = parseStatusCommand(request.status());
		ChangeUserStatusCommand command = new ChangeUserStatusCommand(userId, targetStatus);

		UserStatusResult result = userUseCase.changeUserStatus(command);
		return ApiResult.success(UserStatusResponse.from(result));
	}

	// 목록 필터용 — "ALL"은 필터 없음(null)으로 취급.
	private static UserStatus parseStatusFilter(String statusParam) {
		return switch (statusParam) {
			case "active" -> UserStatus.ACTIVE;
			case "suspended" -> UserStatus.BLOCKED;
			case "withdrawn" -> UserStatus.WITHDRAWN;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}

	// 상태변경 커맨드용 — "ALL"/미인식 값은 전부 400(원본 user-service엔 이 가드가
	// 없어 "ALL" 입력 시 서비스 계층에서 NullPointerException·500이 났던 버그를 고쳤다).
	private static UserStatus parseStatusCommand(String statusParam) {
		return switch (statusParam) {
			case "active" -> UserStatus.ACTIVE;
			case "suspended" -> UserStatus.BLOCKED;
			case "withdrawn" -> UserStatus.WITHDRAWN;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}

	private static UserRole parseRoleFilter(String roleParam) {
		return switch (roleParam) {
			case "buyer" -> UserRole.BUYER;
			case "seller" -> UserRole.SELLER;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}
}
