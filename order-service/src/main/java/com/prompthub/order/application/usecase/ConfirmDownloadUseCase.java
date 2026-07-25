package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.response.ProductDownloadResponse;

import java.util.UUID;

public interface ConfirmDownloadUseCase {

	ProductDownloadResponse confirmDownload(UUID buyerId, UUID orderId, UUID orderProductId);
}
