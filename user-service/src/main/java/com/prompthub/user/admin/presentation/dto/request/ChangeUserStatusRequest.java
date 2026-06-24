package com.prompthub.user.admin.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChangeUserStatusRequest(
        @NotBlank String status
) {
}
