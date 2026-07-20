package com.prompthub.admin.user.application.dto;

import java.util.List;

public record UserPageResult(
	List<UserSummaryResult> users,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
