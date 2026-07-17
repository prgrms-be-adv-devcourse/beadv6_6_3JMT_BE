package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailProductResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService implements OrderQueryUseCase {

	private final OrderExpirationPolicy expirationPolicy;
	private final OrderRepository orderRepository;
	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;

	@Override
	public OrderForPaymentResult getOrderForPayment(UUID orderId) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		return new OrderForPaymentResult(
			order.getId(),
			order.getBuyerId(),
			order.getTotalOrderAmount(),
			order.getCreatedAt()
		);
	}

	@Override
	public OrderDetailResponse getOrderDetail(UUID buyerId, UUID orderId) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		List<OrderDetailProductResponse> products = order.getOrderProducts().stream()
			.map(this::toOrderDetailProductResponse)
			.toList();

		boolean hasDownloadedProduct = order.getOrderProducts().stream()
			.anyMatch(OrderProduct::isDownloaded);

		return new OrderDetailResponse(
			order.getId(),
			order.getOrderNumber(),
			order.getBuyerId(),
			order.getOrderStatus(),
			products,
			order.getTotalOrderAmount(),
			order.getTotalProductCount(),
			order.getPaidAt(),
			order.getCanceledAt(),
			order.getRefundedAt(),
			order.getCreatedAt(),
			hasDownloadedProduct
		);
	}

	@Override
	public OrderContentResponse getOrderContent(UUID buyerId, UUID orderId, UUID orderProductId) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		OrderProduct orderProduct = order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED));

		if (!order.canAccessContent(orderProduct)) {
			throw new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED);
		}

		ProductContent productContent = productClient.getProductContent(orderProduct.getProductId());

		return new OrderContentResponse(
			order.getId(),
			orderProduct.getId(),
			order.getOrderNumber(),
			orderProduct.getProductId(),
			orderProduct.isDownloaded(),
			orderProduct.getProductTitle(),
			productContent.content()
		);
	}

	@Override
	public OrderPaymentValidationResponse validatePaymentReady(
		UUID buyerId,
		UUID orderId,
		int amount,
		LocalDateTime now
	) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		if (!order.isPending()) {
			throw new OrderException(ErrorCode.ORDER_ALREADY_PROCESSED);
		}

		if (order.isExpired(now, expirationPolicy.paymentTimeoutMinutes())) {
			throw new OrderException(ErrorCode.ORDER_EXPIRED);
		}

		if (order.getTotalOrderAmount() != amount) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		}

		return new OrderPaymentValidationResponse(
			true,
			order.getId(),
			order.getBuyerId(),
			order.getTotalOrderAmount(),
			order.getCreatedAt().plusMinutes(expirationPolicy.paymentTimeoutMinutes())
		);
	}

	@Override
	public Page<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request) {
		LocalDateTime from = request.from() == null ? null : request.from().atStartOfDay();
		LocalDateTime to = request.to() == null ? null : request.to().atTime(23, 59, 59);

		PageRequest pageable = PageRequest.of(
			request.page() - 1,
			request.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);

		Page<OrderListProjection> orders = orderRepository.searchOrderproducts(
			buyerId,
			request.status(),
			from,
			to,
			pageable
		);

		return orders.map(this::toOrderListResponse);
	}

	private OrderListResponse toOrderListResponse(OrderListProjection projection) {
		return new OrderListResponse(
			projection.orderId(),
			projection.orderProductId(),
			projection.productId(),
			projection.orderStatus(),
			orderPolicyService.isRefundable(
				projection.orderStatus(),
				projection.orderProductStatus(),
				projection.downloaded()
			),
			projection.productType(),
			projection.title(),
			projection.model(),
			projection.rating(),
			projection.paidAt(),
			projection.createdAt()
		);
	}

	private OrderDetailProductResponse toOrderDetailProductResponse(OrderProduct orderProduct) {
		return new OrderDetailProductResponse(
			orderProduct.getId(),
			orderProduct.getProductId(),
			orderProduct.getSellerId(),
			orderProduct.getProductTitle(),
			orderProduct.getProductType(),
			orderProduct.getProductModel(),
			orderProduct.getProductAmount(),
			orderProduct.getOrderStatus(),
			orderProduct.isPaid(),
			orderProduct.isRefundable(),
			orderProduct.isDownloaded()
		);
	}
}
