package com.prompthub.order.application.service;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ProductClient productClient;

	@Override
	public CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request) {
		//TODO: 1.요청 값 검증, buyerId는 이미 검증된 id
		validateRequest(request);

		//TODO: 2. product-service에서 productIds로 각각 주문용 상품 정보 조회 (productId, sellerId, title, productType, amount, status)
		List<ProductOrderSnapshot> products = productClient.getOrderSnapshots(request.productIds());
		validateProductSnapshots(request.productIds(), products);

		//TODO: 3. 주문 금액 계산
		int totalAmount = products.stream().mapToInt(ProductOrderSnapshot::amount).sum();
		int totalCount = products.size();

		//TODO: 4. Order, OrderProduct 생성
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
				it.getProductTitleSnapshot(),
				it.getProductTypeSnapshot(),
				it.getProductAmountSnapshot(),
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

	@Transactional
	public void approveOrder(PaymentApprovedEvent event) {
		Order order = orderRepository.findByIdWithOrderProducts(event.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (order.isPaid()) {
			return;
		}

		if (!order.isPending()) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_STATUS_INVALID);
		}

		if (order.getTotalOrderAmount() != event.approvedAmount()) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		}

		order.markPaid();

		List<UUID> orderedProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.toList();

		cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
			.ifPresent(cart -> cart.removeProductsByProductIds(orderedProductIds));
	}

	private void validateRequest(CreateOrderRequest request) {
		if (request.productIds() == null || request.productIds().isEmpty()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}

		Set<UUID> uniqueProductIds = new HashSet<>(request.productIds());

		if (uniqueProductIds.size() != request.productIds().size()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private void validateProductSnapshots(
		List<UUID> requestedProductIds,
		List<ProductOrderSnapshot> products
	) {
		if (products == null || products.size() != requestedProductIds.size()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE, "주문 가능한 상품 정보가 올바르지 않습니다.");
		}

		Set<UUID> requestedIds = new HashSet<>(requestedProductIds);
		Set<UUID> responseIds = products.stream()
			.map(ProductOrderSnapshot::productId)
			.collect(java.util.stream.Collectors.toSet());

		if (!responseIds.containsAll(requestedIds)) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE, "조회되지 않은 상품이 포함되어 있습니다.");
		}
	}
}
