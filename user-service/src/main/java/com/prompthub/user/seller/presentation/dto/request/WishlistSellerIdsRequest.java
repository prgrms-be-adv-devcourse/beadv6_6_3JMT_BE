package com.prompthub.user.seller.presentation.dto.request;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Schema(description = "Wishlist 판매자 이름 다건 조회 요청")
public record WishlistSellerIdsRequest(
        @Schema(description = "조회할 판매자 ID(UUID) 목록, 최대 30개",
                example = "[\"3f1b1b0e-5e3a-4c7f-8d9a-1b2c3d4e5f60\"]")
        @NotEmpty @Size(max = 30) List<UUID> sellerIds
) {
    public List<String> sellerIdStrings() {
        return sellerIds.stream().map(UUID::toString).toList();
    }
}
