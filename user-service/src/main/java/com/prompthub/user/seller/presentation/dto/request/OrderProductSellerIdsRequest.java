package com.prompthub.user.seller.presentation.dto.request;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Schema(description = "구매한 상품 판매자 이름 다건 조회 요청")
public record OrderProductSellerIdsRequest(
        @Schema(
                description = "구매한 상품의 판매자 ID(UUID) 목록, 최대 30개",
                example = "[\"3f1b1b0e-1111-2222-3333-444444444444\"]"
        )
        @NotEmpty @Size(max = 30) List<UUID> sellerIds
) {

    public List<String> sellerIdStrings() {
        return sellerIds.stream().map(UUID::toString).toList();
    }
}
