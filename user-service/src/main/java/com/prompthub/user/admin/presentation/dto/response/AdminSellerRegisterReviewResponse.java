package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminSellerRegisterReviewResult;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "판매자 신청 심사 결과 응답")
public record AdminSellerRegisterReviewResponse(
        @Schema(description = "판매자 등록 신청 ID")
        String registerId,
        @Schema(description = "대상 사용자 ID")
        String userId,
        @Schema(description = "처리 상태 (approved | rejected)", example = "approved")
        String status,
        @Schema(description = "반려 사유 — 반려 시에만 포함", nullable = true)
        String rejectReason,
        @Schema(description = "심사 완료 일시 (ISO 8601)", example = "2026-06-17T10:00:00")
        LocalDateTime reviewedAt
) {
    public static AdminSellerRegisterReviewResponse from(AdminSellerRegisterReviewResult result) {
        return new AdminSellerRegisterReviewResponse(
                result.registerId().toString(),
                result.userId().toString(),
                mapStatus(result.status()),
                result.rejectReason(),
                result.reviewedAt()
        );
    }

    private static String mapStatus(SellerRegisterStatus status) {
        return switch (status) {
            case PENDING -> "pending";
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
        };
    }
}
