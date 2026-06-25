package com.prompthub.user.user.presentation.dto.response;

import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.user.application.dto.UserResult;
import com.prompthub.user.user.domain.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "내 프로필 조회 응답")
public record UserProfileResponse(
        @Schema(description = "사용자 ID")
        UUID id,
        @Schema(description = "이름", example = "김민서")
        String name,
        @Schema(description = "이메일", example = "user@example.com")
        String email,
        @Schema(description = "프로필 이미지 URL", nullable = true)
        String profileImageUrl,
        @Schema(description = "역할 (BUYER / SELLER)", example = "BUYER")
        UserRole role,
        @Schema(description = "판매자 신청 상태 — null: 신청 이력 없는 BUYER, PENDING: 심사 대기, APPROVED: 승인, REJECTED: 반려", nullable = true)
        SellerRegisterStatus sellerStatus
) {
    public static UserProfileResponse from(UserResult result) {
        return new UserProfileResponse(
                result.userId(),
                result.name(),
                result.email(),
                result.profileImageUrl(),
                result.role(),
                result.sellerStatus()
        );
    }
}
