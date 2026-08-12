package com.prompthub.product.presentation.dto.response.product;

import java.util.UUID;

public record ProductCountResponse(
	UUID sellerId,
	long productCount,
	long salesCount
) {
}
