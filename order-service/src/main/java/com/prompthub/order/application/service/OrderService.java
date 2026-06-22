package com.prompthub.order.application.service;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
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
	private final CartRepository cartRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;

	@Override
	public CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request) {
		orderPolicyService.validateCreateOrderRequest(request);

		//TODO: product-service에서 productIds로 각각 주문용 상품 정보 조회 (productId, sellerId, title, productType, amount, status)
		List<ProductOrderSnapshot> products = productClient.getOrderSnapshots(request.productIds());
		orderPolicyService.validateProductSnapshots(request.productIds(), products);

		int totalAmount = products.stream().mapToInt(ProductOrderSnapshot::amount).sum();
		int totalCount = products.size();

		String orderNumber = orderNumberGenerator.generate();
		Order order = Order.create(buyerId, orderNumber, totalAmount, totalCount);
		products.stream()
			.map(it -> OrderProduct.create(it.productId(), it.sellerId(), it.title(), it.productType(), it.amount()))
			.forEach(order::addOrderProduct);

		Order savedOrder = orderRepository.save(order);
		var responseProducts = savedOrder.getOrderProducts().stream()
			.map(it -> new OrderProductsResponse(
				it.getId(),
				it.getProductId(),
				it.getSellerId(),
				it.getProductTitle(),
				it.getProductType(),
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
	public PageResponse<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request) {
		PageRequestParams resolvedRequest = request.resolve();
		int page = resolvedRequest.page();
		int size = resolvedRequest.size();

		LocalDateTime from = resolvedRequest.from() == null ? null : resolvedRequest.from().atStartOfDay();
		LocalDateTime to = resolvedRequest.to() == null ? null : resolvedRequest.to().atTime(23, 59, 59);

		PageRequest pageable = PageRequest.of(
			page - 1,
			size,
			Sort.by(Sort.Direction.DESC, "createdAt")
		);

		Page<OrderListProjection> orders = orderRepository.searchOrderproducts(
			buyerId,
			resolvedRequest.status(),
			from,
			to,
			pageable
		);

		List<OrderListResponse> data = orders.getContent().stream()
			.map(this::toOrderListResponse)
			.toList();

		return PageResponse.success(data, page, size, orders.getTotalElements(), orders.hasNext());
	}

	@Transactional
	public void approveOrder(PaymentApprovedEvent event) throws OrderException {
		Order order = orderRepository.findByIdWithOrderProducts(event.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (order.isPaid()) {
			return;
		}

		orderPolicyService.validatePaymentApproval(order, event);
		order.markPaid();

		List<UUID> orderedProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.toList();

		cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
			.ifPresent(cart -> cart.removeProductsByProductIds(orderedProductIds));
	}

	private OrderListResponse toOrderListResponse(OrderListProjection projection) {
		return new OrderListResponse(
			projection.orderId(),
			projection.orderProductId(),
			projection.orderStatus(),
			orderPolicyService.isRefundable(projection.orderStatus(), projection.orderProductStatus(),
				projection.download()),
			projection.productType(),
			projection.title(),
			projection.model(),
			projection.rating(),
			// projection.thumbnailUrl(),
			projection.paidAt(),
			projection.createdAt()
		);
	}
}
