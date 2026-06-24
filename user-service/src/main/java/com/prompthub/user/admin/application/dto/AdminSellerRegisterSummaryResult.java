package com.prompthub.user.admin.application.dto;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminSellerRegisterSummaryResult(
        UUID registerId,
        UUID userId,
        String name,
        String email,
        String introduction,
        List<String> categories,
        String portfolioUrl,
        SellerRegisterStatus status,
        LocalDateTime submittedAt
) {
    public static AdminSellerRegisterSummaryResult of(SellerRegister register, User user) {
        return new AdminSellerRegisterSummaryResult(
                register.getSellerRegisterId(),
                register.getUserId(),
                user.getName(),
                user.getEmail(),
                register.getIntroduction(),
                List.copyOf(register.getCategories()),
                register.getPortfolioUrl(),
                register.getStatus(),
                register.getSubmittedAt()
        );
    }
}
