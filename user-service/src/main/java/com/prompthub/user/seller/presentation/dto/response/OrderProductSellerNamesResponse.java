package com.prompthub.user.seller.presentation.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "구매한 상품 판매자 이름 다건 조회 응답")
public record OrderProductSellerNamesResponse(
        @Schema(description = "구매한 상품의 판매자 이름 목록")
        List<Item> sellers
) {

    public static OrderProductSellerNamesResponse of(
            List<UUID> requestedSellerIds,
            List<SellerInfoResult> results
    ) {
        Map<String, String> nameById = results.stream()
                .collect(Collectors.toMap(SellerInfoResult::sellerId, SellerInfoResult::sellerName));

        List<Item> items = requestedSellerIds.stream()
                .distinct()
                .map(sellerId -> new Item(sellerId, nameById.get(sellerId.toString())))
                .toList();

        return new OrderProductSellerNamesResponse(items);
    }

    @Schema(description = "구매한 상품 판매자 이름 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)", example = "3f1b1b0e-1111-2222-3333-444444444444")
            UUID sellerId,

            @Schema(description = "판매자 이름, 조회되지 않은 sellerId면 null", nullable = true)
            String sellerName
    ) {
    }
}
