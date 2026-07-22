package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserRoleResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "사용자 역할 변경 응답")
public record UserRoleResponse(
	@Schema(description = "사용자 ID")
	String id,
	@Schema(description = "변경된 역할 (buyer | seller)", example = "seller")
	String role,
	@Schema(description = "변경 일시 (ISO 8601)", example = "2026-07-21T10:00:00")
	LocalDateTime updatedAt
) {
	public static UserRoleResponse from(UserRoleResult result) {
		return new UserRoleResponse(
			result.userId().toString(),
			result.role().name().toLowerCase(),
			result.updatedAt()
		);
	}
}
