package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.RejoinCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "탈퇴 계정 재가입 확인 요청")
public record RejoinRequest(
        @Schema(description = "OAuth 로그인에서 발급된 일회성 재가입 토큰")
        @NotBlank String rejoinToken
) {

    public RejoinCommand toCommand() {
        return new RejoinCommand(rejoinToken);
    }
}
