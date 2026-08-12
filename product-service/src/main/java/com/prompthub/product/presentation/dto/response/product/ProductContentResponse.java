package com.prompthub.product.presentation.dto.response.product;

import java.util.UUID;

public record ProductContentResponse(
	UUID productId,
	String content
) {
}
