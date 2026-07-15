package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ConfirmDownloadCommandHandler implements ConfirmDownloadUseCase {

	private final OrderRepository orderRepository;
	private final ProductClient productClient;

	@Override
	public OrderProductDownloadResponse confirmDownload(UUID buyerId, UUID orderId, UUID orderProductId) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		OrderProduct orderProduct = order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND));

		if (!orderProduct.isContentAccessible()) {
			throw new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED);
		}

		productClient.getProductContent(orderProduct.getProductId());
		orderProduct.markDownloaded();

		return new OrderProductDownloadResponse(
			order.getId(),
			orderProduct.getId(),
			orderProduct.isDownloaded(),
			orderProduct.isRefundable()
		);
	}
}
