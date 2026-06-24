package com.prompthub.user.admin.application.dto;

import java.util.List;

public record AdminUserPageResult(
        List<AdminUserSummaryResult> users,
        int page,
        int size,
        long total,
        boolean hasNext
) {
}
