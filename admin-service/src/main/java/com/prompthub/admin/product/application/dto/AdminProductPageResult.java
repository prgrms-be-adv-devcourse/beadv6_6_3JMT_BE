package com.prompthub.admin.product.application.dto;

import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;

public record AdminProductPageResult(
	List<AdminProductListItemResponse> items,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
