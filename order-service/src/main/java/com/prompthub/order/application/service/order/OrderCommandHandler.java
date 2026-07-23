package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderCreationItem;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;

@Service
@RequiredArgsConstructor
public class OrderCommandHandler implements CreateOrderUseCase {

	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;
	private final OrderCreator orderCreator;

	@Override
	public CreateOrderResult createOrder(UUID buyerId, CreateOrderCommand command) {
		orderPolicyService.validateCreateOrderCommand(command);

		List<UUID> productIds = command.products().stream()
			.map(CreateOrderCommand.Product::productId)
			.toList();
		List<ProductOrderSnapshot> snapshots = productClient.getOrderSnapshots(productIds);
		orderPolicyService.validateProductSnapshots(productIds, snapshots);
		orderPolicyService.validateSelfPurchase(buyerId, snapshots);

		Map<UUID, ProductOrderSnapshot> snapshotsByProductId = snapshots.stream()
			.collect(toMap(ProductOrderSnapshot::productId, Function.identity()));
		List<OrderCreationItem> items = command.products().stream()
			.map(product -> toOrderCreationItem(product, snapshotsByProductId.get(product.productId())))
			.toList();

		return orderCreator.create(buyerId, items);
	}

	private OrderCreationItem toOrderCreationItem(
		CreateOrderCommand.Product product,
		ProductOrderSnapshot snapshot
	) {
		return new OrderCreationItem(
			product.productId(),
			snapshot.sellerId(),
			product.productTitle(),
			snapshot.amount()
		);
	}
}
