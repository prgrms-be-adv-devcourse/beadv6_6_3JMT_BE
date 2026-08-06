package com.prompthub.user.seller.presentation.dto.response;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 단건 조회 응답")
public record SellerProfileResponse(
        @Schema(description = "판매자 이름", example = "김철수")
        String sellerName,

        @Schema(description = "프로필 이미지 URL, 미등록 시 null", nullable = true, example = "https://cdn.example.com/images/profile.png")
        String profileImageUrl
) {

    public static SellerProfileResponse from(SellerInfoResult result) {
        String profileImageUrl = result.profileImageUrl() == null || result.profileImageUrl().isEmpty()
                ? null
                : result.profileImageUrl();
        return new SellerProfileResponse(result.sellerName(), profileImageUrl);
    }
}
