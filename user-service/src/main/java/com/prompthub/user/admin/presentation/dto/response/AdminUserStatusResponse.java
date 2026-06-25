package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.user.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "사용자 상태 변경 응답")
public record AdminUserStatusResponse(
        @Schema(description = "사용자 ID")
        String id,
        @Schema(description = "변경된 계정 상태 (active | suspended | withdrawn)", example = "suspended")
        String status,
        @Schema(description = "변경 일시 (ISO 8601)", example = "2026-06-17T10:00:00")
        LocalDateTime updatedAt
) {
    public static AdminUserStatusResponse from(AdminUserStatusResult result) {
        return new AdminUserStatusResponse(
                result.userId().toString(),
                mapStatus(result.status()),
                result.updatedAt()
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
