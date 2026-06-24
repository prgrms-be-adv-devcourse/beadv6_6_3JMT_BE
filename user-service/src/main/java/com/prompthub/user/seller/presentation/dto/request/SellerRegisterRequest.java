package com.prompthub.user.seller.presentation.dto.request;

import com.prompthub.user.seller.application.dto.RegisterSellerCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SellerRegisterRequest(
        @NotNull @Size(min = 1, max = 3) List<String> categories,
        String introduction,
        String portfolioUrl,
        @NotNull @AssertTrue Boolean agreedToTerms
) {
    public RegisterSellerCommand toCommand(UUID userId) {
        return new RegisterSellerCommand(
                userId,
                categories,
                introduction,
                portfolioUrl,
                agreedToTerms
        );
    }
}
