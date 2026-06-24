package com.prompthub.product.presentation.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductCreateResponse(
	UUID productId,
	UUID sellerId,
	String title,
	String category,
	String model,
	String desc,
	int amount,
	String status,
	LocalDateTime createdAt
) {
}
