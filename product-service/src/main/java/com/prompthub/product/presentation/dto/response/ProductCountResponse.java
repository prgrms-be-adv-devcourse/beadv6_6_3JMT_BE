package com.prompthub.product.presentation.dto.response;

import java.util.UUID;

public record ProductCountResponse(
	UUID sellerId,
	long productCount
) {
}
