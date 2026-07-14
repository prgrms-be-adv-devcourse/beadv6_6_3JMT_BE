package com.prompthub.product.presentation.dto.response;

import java.util.UUID;

public record ProductContentResponse(
	UUID productId,
	String content
) {
}
