package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.PaymentStatus;
import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailProductResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
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
@Transactional
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

	private final OrderRepository orderRepository;
	private final OrderPaymentRepository orderPaymentRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;

	@Override
	public CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request) {
		orderPolicyService.validateCreateOrderRequest(request);

		List<ProductOrderSnapshot> products = productClient.getOrderSnapshots(request.productIds());
		orderPolicyService.validateProductSnapshots(request.productIds(), products);

		int totalAmount = products.stream().mapToInt(ProductOrderSnapshot::amount).sum();
		int totalCount = products.size();

		String orderNumber = orderNumberGenerator.generate();
		Order order = Order.create(buyerId, orderNumber, totalAmount, totalCount);
		products.stream()
			.map(it -> OrderProduct.create(it.productId(), it.sellerId(), it.title(), it.productType(), it.model(), it.amount()))
			.forEach(order::addOrderProduct);

		Order savedOrder = orderRepository.save(order);
		var responseProducts = savedOrder.getOrderProducts().stream()
			.map(it -> new OrderProductsResponse(
				it.getId(),
				it.getProductId(),
				it.getSellerId(),
				it.getProductTitle(),
				it.getProductType(),
				it.getProductModel(),
				it.getProductAmount(),
				it.getOrderStatus()
			))
			.toList();

		return new CreateOrderResponse(
			savedOrder.getId(),
			savedOrder.getOrderNumber(),
			savedOrder.getBuyerId(),
			savedOrder.getOrderStatus(),
			responseProducts,
			savedOrder.getTotalOrderAmount(),
			savedOrder.getCreatedAt(),
			savedOrder.getCanceledAt()
		);
	}

	@Override
	@Transactional(readOnly = true)
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

		if (!order.isPaid()) {
			throw new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED);
		}

		OrderProduct orderProduct = order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED));

		if (!orderProduct.isPaid()) {
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

		if (!order.isPaid() || !orderProduct.isPaid()) {
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

	@Override
	@Transactional(readOnly = true)
	public Page<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request) {
		int page = request.page();
		int size = request.size();

		LocalDateTime from = request.from() == null ? null : request.from().atStartOfDay();
		LocalDateTime to = request.to() == null ? null : request.to().atTime(23, 59, 59);

		PageRequest pageable = PageRequest.of(
			page - 1,
			size,
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

	@Override
	@Transactional(readOnly = true)
	public Page<OrderPaymentListResponse> getOrderPayments(UUID buyerId, PageRequestParams request) {
		int page = request.page();
		int size = request.size();

		PageRequest pageable = PageRequest.of(
			page - 1,
			size,
			Sort.by(
				Sort.Order.desc("approvedAt")
			)
		);

		Page<OrderPaymentListProjection> orderPayments = orderPaymentRepository.searchOrderPayments(buyerId, pageable);

		return orderPayments.map(this::toOrderPaymentListResponse);
	}

	private OrderListResponse toOrderListResponse(OrderListProjection projection) {
		return new OrderListResponse(
			projection.orderId(),
			projection.orderProductId(),
			projection.productId(),
			projection.orderStatus(),
			orderPolicyService.isRefundable(projection.orderStatus(), projection.orderProductStatus(), projection.downloaded()),
			projection.productType(),
			projection.title(),
			projection.model(),
			projection.rating(),
			// projection.thumbnailUrl(),
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

	private OrderPaymentListResponse toOrderPaymentListResponse(OrderPaymentListProjection projection) {
		return new OrderPaymentListResponse(
			projection.orderId(),
			projection.paymentId(),
			PaymentStatus.from(projection.orderStatus()),
			projection.isRefundable(),
			projection.productType(),
			projection.title(),
			projection.amount(),
			projection.paidAt() == null ? projection.approvedAt() : projection.paidAt()
		);
	}

}
