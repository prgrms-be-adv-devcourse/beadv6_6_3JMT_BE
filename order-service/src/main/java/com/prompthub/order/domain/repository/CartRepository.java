package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.Cart;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository {

	Optional<Cart> findByBuyerIdWithCartProducts(UUID buyerId);
}
