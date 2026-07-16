package com.prompthub.order.application.dto;

import java.util.List;
import java.util.UUID;

public record CreateOrderCommand(
	List<Product> products
) {

	public record Product(
		UUID productId,
		String productTitle
	) {
	}
}
