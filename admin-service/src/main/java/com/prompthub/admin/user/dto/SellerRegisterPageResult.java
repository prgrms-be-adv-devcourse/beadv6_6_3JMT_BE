package com.prompthub.admin.user.dto;

import java.util.List;

public record SellerRegisterPageResult(
	List<SellerRegisterSummaryResult> items,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
