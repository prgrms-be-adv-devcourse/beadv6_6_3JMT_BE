package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminSellerRegisterReviewResult;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;

public record AdminSellerRegisterReviewResponse(
        String registerId,
        String userId,
        String status,
        String rejectReason,
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
