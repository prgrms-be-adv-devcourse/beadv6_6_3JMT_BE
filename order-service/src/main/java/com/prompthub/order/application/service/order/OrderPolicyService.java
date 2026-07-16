package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
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
	private static final int MAX_PRODUCT_TITLE_LENGTH = 200;

	public void validateCreateOrderCommand(CreateOrderCommand command) {
		if (command == null || command.products() == null || command.products().isEmpty()) {
			throw invalidInput();
		}

		Set<UUID> uniqueProductIds = new HashSet<>();
		for (CreateOrderCommand.Product product : command.products()) {
			if (product == null
				|| product.productId() == null
				|| product.productTitle() == null
				|| product.productTitle().isBlank()
				|| product.productTitle().length() > MAX_PRODUCT_TITLE_LENGTH
				|| !uniqueProductIds.add(product.productId())) {
				throw invalidInput();
			}
		}
	}

	public void validateProductSnapshots(
		List<UUID> requestedProductIds,
		List<ProductOrderSnapshot> products
	) {
		if (products == null || products.size() != requestedProductIds.size()) {
			throw invalidInput();
		}

		Set<UUID> requestedIds = new HashSet<>(requestedProductIds);
		if (products.stream().anyMatch(product -> product == null
			|| product.productId() == null
			|| product.sellerId() == null
			|| product.amount() <= 0)) {
			throw invalidInput();
		}
		Set<UUID> responseIds = products.stream().map(ProductOrderSnapshot::productId).collect(toSet());

		if (responseIds.size() != products.size() || !responseIds.equals(requestedIds)) {
			throw invalidInput();
		}
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
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
		OrderProductStatus orderProductStatus,
		boolean downloaded
	) {
		return (orderStatus == OrderStatus.COMPLETED || orderStatus == OrderStatus.PARTIAL_REFUNDED)
			&& orderProductStatus == OrderProductStatus.PAID
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

}
