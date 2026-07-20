package com.prompthub.admin.order.application.dto;

import java.time.LocalDate;

public record DailyTransactionProjection(
	LocalDate date,
	long transactionCount,
	long transactionAmount
) {
}
