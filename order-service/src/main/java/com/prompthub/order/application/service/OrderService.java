package com.prompthub.order.application.service;
 
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
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
	private final OrderPaymentRepository orderPaymentRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;
 
	@Override
	public CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request) {
		List<ProductOrderSnapshot> productSnapshots = productClient.getOrderSnapshots(request.productIds());
		orderPolicyService.validateOrderCreation(productSnapshots);
 
		String orderNumber = orderNumberGenerator.generate();
		Order order = Order.create(buyerId, orderNumber, productSnapshots);
 
		Order savedOrder = orderRepository.save(order);
		return CreateOrderResponse.from(savedOrder);
	}
 
	@Override
	public PageResponse<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request) {
		PageRequestParams resolvedRequest = request.resolve();
		int page = resolvedRequest.page();
		int size = resolvedRequest.size();
 
		org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
			page - 1,
			size,
			org.springframework.data.domain.Sort.by(
				org.springframework.data.domain.Sort.Order.desc("paidAt"),
				org.springframework.data.domain.Sort.Order.asc("orderProductId")
			)
		);
 
		org.springframework.data.domain.Page<OrderListProjection> orders = orderRepository.searchOrderProducts(buyerId, resolvedRequest, pageable);
 
		List<OrderListResponse> data = orders.getContent().stream()
			.map(this::toOrderListResponse)
			.toList();
 
		return PageResponse.success(data, page, size, orders.getTotalElements(), orders.hasNext());
	}
 
	@Override
	public void approveOrder(PaymentApprovedEvent event) {
		Order order = orderRepository.findByIdWithOrderProducts(event.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
 
		boolean orderPaymentExists = orderPaymentRepository.existsByOrderId(event.orderId());
		if (orderPaymentExists && order.isPaid()) {
			return;
		}
 
		orderPolicyService.validatePaymentApproval(order, event);
		order.markPaid(event.approvedAt().toLocalDateTime());
		orderPaymentRepository.save(OrderPayment.create(
			order.getId(),
			event.paymentId(),
			order.getBuyerId(),
			event.pgTxId(),
			event.paymentMethod(),
			event.provider(),
			event.approvedAmount(),
			event.approvedAt()
		));
 
		List<UUID> orderedProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.toList();
 
		cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
			.ifPresent(cart -> cart.removeProductsByProductIds(orderedProductIds));
	}
 
	@Override
	public PageResponse<com.prompthub.order.presentation.dto.response.OrderPaymentListResponse> getOrderPayments(UUID buyerId, PageRequestParams request) {
		throw new UnsupportedOperationException("Not implemented yet");
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
			projection.paidAt(),
			projection.createdAt()
		);
	}
}
