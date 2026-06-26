package com.prompthub.user.user.presentation.dto.request;

import com.prompthub.user.user.application.dto.UpdateProfileCommand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "프로필 수정 요청 — 수정할 필드만 포함 (Partial Update)")
public record UpdateProfileRequest(
        @Schema(description = "변경할 이름", example = "새이름", nullable = true)
        String name,
        @Schema(description = "변경할 이메일", example = "new@example.com", nullable = true)
        String email,
        @Schema(description = "변경할 비밀번호 — local 가입 사용자만 허용. OAuth 사용자가 포함하면 400 반환", nullable = true)
        String password
) {
    public UpdateProfileCommand toCommand(UUID userId) {
        return new UpdateProfileCommand(userId, name, email);
    }
}
