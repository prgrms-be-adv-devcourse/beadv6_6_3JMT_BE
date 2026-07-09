package com.prompthub.user.sellersettlement.application.dto;

public record SellerProductStats(int registeredProductCount, long salesCount) {

    public static SellerProductStats empty() {
        return new SellerProductStats(0, 0L);
    }
}
