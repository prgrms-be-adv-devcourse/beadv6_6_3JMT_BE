package com.prompthub.admin.user.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "사용자 상태 변경 요청")
public record ChangeUserStatusRequest(
	@Schema(description = "변경할 계정 상태 (active | suspended | withdrawn)", example = "suspended")
	@NotBlank String status
) {
}
