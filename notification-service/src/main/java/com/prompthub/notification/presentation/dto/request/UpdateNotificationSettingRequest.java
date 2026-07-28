package com.prompthub.notification.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "알림 카테고리 수신 설정 변경 요청")
public record UpdateNotificationSettingRequest(
    @Schema(description = "수신 여부", example = "false")
    @NotNull Boolean enabled
) {
}
