package com.prompthub.admin.product.dto;

import com.prompthub.admin.product.dto.response.AdminProductListItemResponse;
import java.util.List;

public record AdminProductPageResult(
	List<AdminProductListItemResponse> items,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
