package com.prompthub.user.seller.presentation.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 이름 다건 조회 응답")
public record SellerNamesResponse(
        @Schema(description = "판매자 이름 목록 (요청한 sellerId 각각에 대응, 존재하지 않으면 sellerName: null)")
        List<Item> sellers
) {

    public static SellerNamesResponse of(List<UUID> requestedSellerIds, List<SellerInfoResult> results) {
        Map<String, String> nameById = results.stream()
                .collect(Collectors.toMap(SellerInfoResult::sellerId, SellerInfoResult::sellerName));

        List<Item> items = requestedSellerIds.stream()
                .distinct()
                .map(sellerId -> new Item(sellerId, nameById.get(sellerId.toString())))
                .toList();

        return new SellerNamesResponse(items);
    }

    @Schema(description = "판매자 이름 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            @Schema(description = "판매자 이름, 존재하지 않는 sellerId면 null", nullable = true)
            String sellerName
    ) {
    }
}
