package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.Cart;
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
}
