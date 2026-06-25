package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProductRejectRequest(
	@NotBlank String reason
) {
}
