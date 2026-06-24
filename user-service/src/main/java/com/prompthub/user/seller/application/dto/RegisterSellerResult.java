package com.prompthub.user.seller.application.dto;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record RegisterSellerResult(
        UUID sellerRequestId,
        SellerRegisterStatus status,
        LocalDateTime submittedAt
) {
    public static RegisterSellerResult from(SellerRegister register) {
        return new RegisterSellerResult(
                register.getSellerRegisterId(),
                register.getStatus(),
                register.getSubmittedAt()
        );
    }
}
