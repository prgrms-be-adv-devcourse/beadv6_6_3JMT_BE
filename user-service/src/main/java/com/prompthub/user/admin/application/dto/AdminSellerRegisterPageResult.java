package com.prompthub.user.admin.application.dto;

import java.util.List;

public record AdminSellerRegisterPageResult(
        List<AdminSellerRegisterSummaryResult> items,
        int page,
        int size,
        long total,
        boolean hasNext
) {
}
