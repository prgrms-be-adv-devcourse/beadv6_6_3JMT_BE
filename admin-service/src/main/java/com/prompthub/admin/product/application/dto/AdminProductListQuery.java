package com.prompthub.admin.product.application.dto;

import com.prompthub.admin.product.domain.model.enums.ProductStatus;

public record AdminProductListQuery(
	ProductStatus status,
	String keyword,
	int page,
	int size
) {
}
