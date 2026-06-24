package com.prompthub.user.admin.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RejectSellerRegisterRequest(@NotBlank String rejectReason) {
}
