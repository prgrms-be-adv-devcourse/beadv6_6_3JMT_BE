package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository {

	Optional<Cart> findByBuyerIdWithCartProducts(UUID buyerId);

	Optional<CartProduct> findCartProductWithCart(UUID cartProductId);

	Cart save(Cart cart);
}
