package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 — 사용자 목록 항목")
public record UserResponse(
	@Schema(description = "사용자 ID")
	String id,
	@Schema(description = "이름", example = "김도윤")
	String name,
	@Schema(description = "이메일", example = "doyoon.kim@gmail.com")
	String email,
	@Schema(description = "역할 (buyer | seller)", example = "buyer")
	String role,
	@Schema(description = "계정 상태 (active | suspended | withdrawn)", example = "active")
	String status
) {
	public static UserResponse from(UserSummaryResult result) {
		return new UserResponse(
			result.userId().toString(),
			result.name(),
			result.email(),
			result.role().name().toLowerCase(),
			mapStatus(result.status())
		);
	}

	private static String mapStatus(UserStatus status) {
		return switch (status) {
			case ACTIVE -> "active";
			case BLOCKED -> "suspended";
			case WITHDRAWN -> "withdrawn";
		};
	}
}
