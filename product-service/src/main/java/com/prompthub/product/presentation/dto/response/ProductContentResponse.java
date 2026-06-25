package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductContentResponse(
	UUID productId,
	String content
) {

	public static ProductContentResponse from(Product product) {
		return new ProductContentResponse(product.getId(), product.getContent());
	}
}
