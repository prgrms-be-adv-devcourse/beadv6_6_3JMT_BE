package com.prompthub.order.presentation.dto.response;

import java.util.UUID;

public record OrderContentResponse(
	UUID orderId,
	UUID orderProductId,
	String orderNumber,
	UUID productId,
	boolean isDownload,
	String productTitle,
	String content
) {
}
