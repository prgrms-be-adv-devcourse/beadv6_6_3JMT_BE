package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderCreationItem;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreator {

	private final OrderNumberGenerator orderNumberGenerator;
	private final OrderProductReservationService reservationService;
	private final OrderCreationTransactionService transactionService;

	public CreateOrderResult create(UUID buyerId, List<OrderCreationItem> items) {
		int totalAmount = OrderAmountCalculator.sum(items, OrderCreationItem::amount);
		Order order = Order.create(
			buyerId,
			orderNumberGenerator.generate(),
			totalAmount
		);
		items.stream()
			.map(item -> OrderProduct.create(
				item.productId(),
				item.sellerId(),
				item.productTitle(),
				item.amount()
			))
			.forEach(order::addOrderProduct);
		if (order.isFree()) {
			order.completeFreeOrder();
		}

		reservationService.reserve(order);
		try {
			return transactionService.create(order);
		} catch (RuntimeException exception) {
			reservationService.releaseAfterFailure(order);
			throw exception;
		}
	}
}
