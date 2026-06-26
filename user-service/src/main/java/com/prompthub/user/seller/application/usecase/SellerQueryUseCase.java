package com.prompthub.user.seller.application.usecase;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import java.util.List;

public interface SellerQueryUseCase {
    List<SellerInfoResult> findSellers(List<String> sellerIds);
    SellerInfoResult findSeller(String sellerId);
}
