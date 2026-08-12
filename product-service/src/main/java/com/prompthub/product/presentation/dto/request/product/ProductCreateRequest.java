package com.prompthub.product.presentation.dto.request.product;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ProductCreateRequest(
	@NotBlank String title,
	String productType,
	String model,
	@NotBlank String desc,
	@NotNull @Min(0) Integer amount,
	String content,
	String fileObjectKey,
	String externalUrl,
	String thumbnailObjectKey,
	List<String> imageObjectKeys,
	List<String> tags
) {
}
