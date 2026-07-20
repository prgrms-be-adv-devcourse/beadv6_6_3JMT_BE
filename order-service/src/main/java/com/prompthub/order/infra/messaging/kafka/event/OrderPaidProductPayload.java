package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.OrderProduct;

import java.util.UUID;

public record OrderPaidProductPayload(
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        String productTitle,
        String productType,
        int productAmount
) {
	public static OrderPaidProductPayload from(OrderProduct orderProduct) {
		return new OrderPaidProductPayload(
			orderProduct.getId(),
			orderProduct.getProductId(),
			orderProduct.getSellerId(),
			orderProduct.getProductTitle(),
			orderProduct.getProductType(),
			orderProduct.getProductAmount()
		);
	}
}
