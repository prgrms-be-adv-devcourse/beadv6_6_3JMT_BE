package com.prompthub.order.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.global.exception.CartException;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class CartService implements CartUseCase {

	private static final String ON_SALE = "ON_SALE";

	private final CartRepository cartRepository;
	private final ProductClient productClient;

	@Override
	@Transactional(readOnly = true)
	public CartResponse getCart(UUID buyerId) {
		return cartRepository.findByBuyerIdWithCartProducts(buyerId)
			.map(this::toCartResponse)
			.orElseGet(() -> new CartResponse(
				null,
				buyerId,
				Collections.emptyList(),
				0,
				0
			));
	}

	@Override
	public AddCartProductResponse addCartProduct(UUID buyerId, AddCartProductRequest request) {
		ProductCartSnapshot snapshot = productClient.getCartSnapshot(request.productId());
		validateOnSale(snapshot);

		Cart cart = cartRepository.findByBuyerIdWithCartProducts(buyerId)
			.orElseGet(() -> Cart.create(buyerId));

		if (cart.containsProduct(request.productId())) {
			throw new CartException(ErrorCode.CART_ITEM_DUPLICATED);
		}

		CartProduct cartProduct = cart.addProduct(request.productId());
		cartRepository.save(cart);

		return toAddCartProductResponse(cartProduct, snapshot);
	}

	@Override
	public void deleteCartProduct(UUID buyerId, UUID cartProductId) {
		CartProduct cartProduct = cartRepository.findCartProductWithCart(cartProductId)
			.orElseThrow(() -> new CartException(ErrorCode.CART_PRODUCT_NOT_FOUND));

		Cart cart = cartProduct.getCart();
		if (!cart.getBuyerId().equals(buyerId)) {
			throw new CartException(ErrorCode.CART_ITEM_FORBIDDEN);
		}

		cart.removeProduct(cartProductId);
		cartRepository.save(cart);
	}

	private CartResponse toCartResponse(Cart cart) {
		List<CartProduct> cartProducts = cart.getCartProducts();
		if (cartProducts.isEmpty()) {
			return new CartResponse(
				cart.getId(),
				cart.getBuyerId(),
				Collections.emptyList(),
				0,
				0
			);
		}

		List<UUID> productIds = cartProducts.stream()
			.map(CartProduct::getProductId)
			.toList();
		Map<UUID, ProductCartSnapshot> snapshotsByProductId = productClient.getCartSnapshots(productIds)
			.stream()
			.collect(Collectors.toMap(ProductCartSnapshot::productId, Function.identity()));

		List<CartProductResponse> products = cartProducts.stream()
			.map(cartProduct -> toCartProductResponse(cartProduct, findSnapshot(snapshotsByProductId, cartProduct)))
			.toList();
		int totalAmount = products.stream()
			.mapToInt(CartProductResponse::productAmount)
			.sum();

		return new CartResponse(
			cart.getId(),
			cart.getBuyerId(),
			products,
			totalAmount,
			products.size()
		);
	}

	private ProductCartSnapshot findSnapshot(
		Map<UUID, ProductCartSnapshot> snapshotsByProductId,
		CartProduct cartProduct
	) {
		ProductCartSnapshot snapshot = snapshotsByProductId.get(cartProduct.getProductId());
		if (snapshot == null) {
			throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}

		return snapshot;
	}

	private void validateOnSale(ProductCartSnapshot snapshot) {
		if (!ON_SALE.equals(snapshot.status())) {
			throw new CartException(ErrorCode.PRODUCT_NOT_ON_SALE);
		}
	}

	private CartProductResponse toCartProductResponse(
		CartProduct cartProduct,
		ProductCartSnapshot snapshot
	) {
		return new CartProductResponse(
			cartProduct.getId(),
			snapshot.productId(),
			snapshot.title(),
			snapshot.productType(),
			snapshot.amount(),
			snapshot.thumbnailUrl(),
			snapshot.sellerId(),
			snapshot.sellerNickname(),
			snapshot.status(),
			cartProduct.getAddedAt()
		);
	}

	private AddCartProductResponse toAddCartProductResponse(
		CartProduct cartProduct,
		ProductCartSnapshot snapshot
	) {
		return new AddCartProductResponse(
			cartProduct.getId(),
			snapshot.productId(),
			snapshot.title(),
			snapshot.productType(),
			snapshot.amount(),
			snapshot.thumbnailUrl(),
			snapshot.sellerId(),
			snapshot.sellerNickname(),
			snapshot.status(),
			cartProduct.getAddedAt()
		);
	}
}
