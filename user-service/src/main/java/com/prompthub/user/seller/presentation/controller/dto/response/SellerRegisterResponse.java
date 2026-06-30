package com.prompthub.user.seller.presentation.controller.dto.response;

import com.prompthub.user.seller.application.dto.RegisterSellerResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "판매자 등록 신청 응답")
public record SellerRegisterResponse(
        @Schema(description = "판매자 등록 신청 ID")
        UUID sellerRequestId,
        @Schema(description = "신청 상태", example = "PENDING")
        String status,
        @Schema(description = "신청 일시 (ISO 8601)", example = "2025-06-17T10:00:00")
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
