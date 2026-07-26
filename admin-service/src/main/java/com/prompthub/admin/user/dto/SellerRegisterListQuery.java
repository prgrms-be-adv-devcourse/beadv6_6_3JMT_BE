package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.enums.SellerRegisterStatus;

public record SellerRegisterListQuery(
	SellerRegisterStatus status,
	int page,
	int size
) {
}
