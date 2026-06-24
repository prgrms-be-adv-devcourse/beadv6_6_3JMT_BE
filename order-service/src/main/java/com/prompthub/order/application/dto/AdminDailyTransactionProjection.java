package com.prompthub.order.application.dto;

import java.time.LocalDate;

public record AdminDailyTransactionProjection(
	LocalDate date,
	long transactionCount,
	long transactionAmount
) {
}
