package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminSellerRegisterSummaryResult;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AdminSellerRegisterResponse(
        String registerId,
        String userId,
        String name,
        String email,
        String introduction,
        List<String> categories,
        String portfolioUrl,
        String status,
        LocalDateTime submittedAt
) {
    public static AdminSellerRegisterResponse from(AdminSellerRegisterSummaryResult result) {
        return new AdminSellerRegisterResponse(
                result.registerId().toString(),
                result.userId().toString(),
                result.name(),
                result.email(),
                result.introduction(),
                result.categories(),
                result.portfolioUrl(),
                mapStatus(result.status()),
                result.submittedAt()
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
