package com.prompthub.user.admin.application.dto;

import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

public record AdminSellerRegisterListQuery(
        SellerRegisterStatus status,
        int page,
        int size
) {
}
