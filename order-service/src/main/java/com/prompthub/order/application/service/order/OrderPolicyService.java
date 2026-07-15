package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.toSet;

@Service
public class OrderPolicyService {

	private static final int DEFAULT_PAGE = 1;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	public void validateCreateOrderRequest(CreateOrderRequest request) {
		if (request.productIds() == null || request.productIds().isEmpty()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}

		Set<UUID> uniqueProductIds = new HashSet<>(request.productIds());

		if (uniqueProductIds.size() != request.productIds().size()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	public void validateProductSnapshots(
		List<UUID> requestedProductIds,
		List<ProductOrderSnapshot> products
	) {
		if (products == null || products.size() != requestedProductIds.size()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}

		Set<UUID> requestedIds = new HashSet<>(requestedProductIds);
		Set<UUID> responseIds = products.stream()
			.map(ProductOrderSnapshot::productId)
			.collect(toSet());

		if (!responseIds.containsAll(requestedIds)) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	public int resolvePage(Integer page) {
		if (page == null) {
			return DEFAULT_PAGE;
		}

		if (page < 1) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}

		return page;
	}

	public int resolveSize(Integer size) {
		if (size == null) {
			return DEFAULT_SIZE;
		}

		if (size < 1 || size > MAX_SIZE) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}

		return size;
	}

	public void validateDateRange(PageRequestParams request) {
		if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	public boolean isRefundable(
		OrderStatus orderStatus,
		OrderStatus orderProductStatus,
		boolean downloaded
	) {
		return (orderStatus == OrderStatus.PAID || orderStatus == OrderStatus.PARTIAL_REFUNDED)
			&& orderProductStatus == OrderStatus.PAID
			&& !downloaded;
	}

	public boolean isRefundable(Order order) {
		return order.isPaid() && order.getOrderProducts().stream().noneMatch(OrderProduct::isDownloaded);
	}

	public void validateNoDownloadedProduct(Order order) {
		boolean hasDownloadedProduct = order.getOrderProducts().stream()
			.anyMatch(OrderProduct::isDownloaded);

		if (hasDownloadedProduct) {
			throw new OrderException(ErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
	}

	public void validatePaymentApproval(Order order, PaymentApprovedPayload payload) {
		if (!order.isPending() && order.getOrderStatus() != OrderStatus.FAILED) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_STATUS_INVALID);
		}

		if (order.getTotalOrderAmount() != payload.approvedAmount()) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		}
	}
}
