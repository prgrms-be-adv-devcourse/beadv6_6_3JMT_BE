package com.prompthub.order.application.client;

import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductOrderSnapshot;

import java.util.List;
import java.util.UUID;

public interface ProductClient {

    List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds);

    ProductCartSnapshot getCartSnapshot(UUID productId);

    List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds);

    ProductContent getProductContent(UUID productId);
}
