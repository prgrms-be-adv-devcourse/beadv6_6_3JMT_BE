package com.prompthub.admin.order.dto;

import java.time.LocalDate;

public record DailyTransactionProjection(
	LocalDate date,
	long transactionCount,
	long transactionAmount
) {
}
