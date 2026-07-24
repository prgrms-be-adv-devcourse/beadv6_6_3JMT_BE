package com.prompthub.admin.product.application.dto;

import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import org.springframework.data.domain.Pageable;

public record AdminProductListQuery(
	ProductStatus status,
	String keyword,
	Pageable pageable
) {
}
