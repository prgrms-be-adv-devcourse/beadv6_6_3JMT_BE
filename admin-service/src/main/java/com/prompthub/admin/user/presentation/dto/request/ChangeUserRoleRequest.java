package com.prompthub.admin.user.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "사용자 역할 변경 요청")
public record ChangeUserRoleRequest(
	@Schema(description = "변경할 역할 (buyer | seller)", example = "seller")
	@NotBlank String role
) {
}
