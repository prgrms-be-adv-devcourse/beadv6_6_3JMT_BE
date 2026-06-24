package com.prompthub.product.application.client;

import java.util.UUID;

public record SellerInfo(
	UUID sellerId,
	String sellerName,
	String profileImageUrl,
	String status
) {
}
