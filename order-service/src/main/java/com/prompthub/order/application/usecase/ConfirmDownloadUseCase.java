package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;

import java.util.UUID;

public interface ConfirmDownloadUseCase {

	OrderProductDownloadResponse confirmDownload(UUID buyerId, UUID orderId, UUID orderProductId);
}
