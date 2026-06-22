package com.prompthub.order.application.client;

import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;

import java.util.List;
import java.util.UUID;

public interface ProductClient {

    List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds);

    ProductContent getProductContent(UUID productId);
}
