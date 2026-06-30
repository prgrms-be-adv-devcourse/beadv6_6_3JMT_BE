package com.prompthub.user.seller.application.dto;

import java.util.List;
import java.util.UUID;

public record RegisterSellerCommand(
        UUID userId,
        List<String> categories,
        String introduction,
        String portfolioUrl,
        boolean agreedToTerms
) {
}
