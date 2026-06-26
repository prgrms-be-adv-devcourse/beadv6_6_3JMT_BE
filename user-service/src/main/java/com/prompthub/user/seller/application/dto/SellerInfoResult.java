package com.prompthub.user.seller.application.dto;

public record SellerInfoResult(
        String sellerId,
        String sellerName,
        String profileImageUrl,
        String status
) {
}
