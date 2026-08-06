package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UploadUrlRequest(
	@NotBlank String purpose,
	@NotBlank String fileName,
	String productType
) {
}
