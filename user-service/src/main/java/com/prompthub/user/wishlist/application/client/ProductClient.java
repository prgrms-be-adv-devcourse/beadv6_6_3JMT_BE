package com.prompthub.user.wishlist.application.client;

import com.prompthub.user.wishlist.application.dto.ProductSummaryDto;

import java.util.List;
import java.util.UUID;

public interface ProductClient {

    List<ProductSummaryDto> getProductsByIds(List<UUID> productIds);
}
