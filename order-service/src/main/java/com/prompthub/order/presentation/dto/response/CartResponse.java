package com.prompthub.order.presentation.dto.response;

import java.util.List;
import java.util.UUID;

public record CartResponse(
	UUID cartId,
	UUID buyerId,
	List<CartProductResponse> products,
	int totalAmount,
	int totalItemCount
) {
}
