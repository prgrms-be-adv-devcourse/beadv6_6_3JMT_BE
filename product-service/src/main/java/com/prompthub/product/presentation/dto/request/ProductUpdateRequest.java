package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ProductUpdateRequest(
	@NotBlank String title,
	@NotBlank String category,
	@NotBlank String model,
	@NotBlank String desc,
	@NotNull @Min(0) Integer amount,
	@NotBlank String content,
	String thumbnailUrl,
	List<String> tags,
	String changeReason,
	String versionType
) {
}
