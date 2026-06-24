package com.prompthub.user.seller.presentation.dto.response;

import com.prompthub.user.seller.application.dto.RegisterSellerResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record SellerRegisterResponse(
        UUID sellerRequestId,
        String status,
        LocalDateTime submittedAt
) {
    public static SellerRegisterResponse from(RegisterSellerResult result) {
        return new SellerRegisterResponse(
                result.sellerRequestId(),
                result.status().name(),
                result.submittedAt()
        );
    }
}
