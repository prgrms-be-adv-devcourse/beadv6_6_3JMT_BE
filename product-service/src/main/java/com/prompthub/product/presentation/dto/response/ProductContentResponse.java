package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductContentResponse(
	UUID productId,
	String content
) {

	public static ProductContentResponse from(UUID productId, Product product) {
		return new ProductContentResponse(productId, product.getContent());
	}
}
