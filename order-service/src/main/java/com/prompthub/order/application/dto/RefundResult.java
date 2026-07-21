package com.prompthub.order.application.dto;

import java.util.List;
import java.util.UUID;

public record RefundResult(
		UUID refundRequestId,
		UUID orderId,
		List<UUID> orderProductIds,
		int refundAmount,
		String status
	) {
	}