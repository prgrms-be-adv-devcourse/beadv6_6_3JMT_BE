package com.prompthub.user.user.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.prompthub.user.user.application.dto.UpdateProfileResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "프로필 수정 응답 — 실제로 변경된 필드만 포함 (미변경 필드 생략)")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateProfileResponse(
        @Schema(description = "사용자 ID (항상 포함)")
        UUID id,
        @Schema(description = "변경된 이름 (수정 시에만 포함)", nullable = true)
        String name,
        @Schema(description = "변경된 이메일 (수정 시에만 포함)", nullable = true)
        String email
) {
    public static UpdateProfileResponse from(UpdateProfileResult result) {
        return new UpdateProfileResponse(result.userId(), result.name(), result.email());
    }
}
