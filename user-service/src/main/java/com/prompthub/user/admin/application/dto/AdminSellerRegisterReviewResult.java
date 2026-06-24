package com.prompthub.user.admin.application.dto;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminSellerRegisterReviewResult(
        UUID registerId,
        UUID userId,
        SellerRegisterStatus status,
        String rejectReason,
        LocalDateTime reviewedAt
) {
    public static AdminSellerRegisterReviewResult from(SellerRegister register) {
        return new AdminSellerRegisterReviewResult(
                register.getSellerRegisterId(),
                register.getUserId(),
                register.getStatus(),
                register.getRejectReason(),
                register.getReviewedAt()
        );
    }
}
