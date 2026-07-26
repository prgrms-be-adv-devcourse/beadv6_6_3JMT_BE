package com.prompthub.admin.user.dto;

import java.util.List;

public record UserPageResult(
	List<UserSummaryResult> users,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
