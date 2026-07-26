package com.prompthub.admin.product.dto;

import com.prompthub.admin.product.entity.enums.ProductStatus;
import org.springframework.data.domain.Pageable;

public record AdminProductListQuery(
	ProductStatus status,
	String keyword,
	Pageable pageable
) {
}
