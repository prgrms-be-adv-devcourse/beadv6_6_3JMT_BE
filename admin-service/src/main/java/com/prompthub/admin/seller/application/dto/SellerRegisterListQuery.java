package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

public record SellerRegisterListQuery(
	SellerRegisterStatus status,
	int page,
	int size
) {
}
