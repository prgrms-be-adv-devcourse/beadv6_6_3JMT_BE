package com.prompthub.user.seller.presentation.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Wishlist 판매자 이름 다건 조회 응답")
public record WishlistSellerNamesResponse(
        @Schema(description = "요청한 판매자별 이름, 존재하지 않으면 sellerName은 null")
        List<Item> sellers
) {

    public static WishlistSellerNamesResponse of(
            List<UUID> requestedSellerIds, List<SellerInfoResult> results) {
        Map<String, String> nameById = results.stream()
                .collect(Collectors.toMap(SellerInfoResult::sellerId, SellerInfoResult::sellerName));

        List<Item> items = requestedSellerIds.stream()
                .distinct()
                .map(sellerId -> new Item(sellerId, nameById.get(sellerId.toString())))
                .toList();

        return new WishlistSellerNamesResponse(items);
    }

    @Schema(description = "Wishlist 판매자 이름 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)", example = "3f1b1b0e-5e3a-4c7f-8d9a-1b2c3d4e5f60")
            UUID sellerId,
            @Schema(description = "판매자 이름, 존재하지 않는 판매자면 null", nullable = true)
            String sellerName
    ) {
    }
}
