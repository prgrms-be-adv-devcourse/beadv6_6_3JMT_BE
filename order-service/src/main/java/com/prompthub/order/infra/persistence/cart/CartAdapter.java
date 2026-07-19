package com.prompthub.order.infra.persistence.cart;

import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CartAdapter implements CartRepository {

	private final CartPersistence cartPersistence;

	@Override
	public Optional<Cart> findByBuyerIdWithCartProducts(UUID buyerId) {
		return cartPersistence.findByBuyerIdWithCartProducts(buyerId);
	}

	@Override
	public Optional<Cart> findByBuyerIdForUpdateWithCartProducts(UUID buyerId) {
		return cartPersistence.findByBuyerIdForUpdate(buyerId)
			.flatMap(ignored -> cartPersistence.findByBuyerIdWithCartProducts(buyerId));
	}

	@Override
	public Optional<CartProduct> findCartProductWithCart(UUID cartProductId) {
		return cartPersistence.findCartProductWithCart(cartProductId);
	}

	@Override
	public Cart save(Cart cart) {
		return cartPersistence.save(cart);
	}
}
