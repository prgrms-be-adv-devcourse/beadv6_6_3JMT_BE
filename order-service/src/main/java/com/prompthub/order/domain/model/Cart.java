package com.prompthub.order.domain.model;

import com.prompthub.order.global.exception.CartException;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "cart")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseEntity {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
	private UUID buyerId;

	@Column(name = "total_amount", nullable = false)
	private int totalAmount;

	@OneToMany(
		mappedBy = "cart",
		cascade = CascadeType.ALL,
		orphanRemoval = true
	)
	private List<CartProduct> cartProducts = new ArrayList<>();

	private Cart(
		UUID id,
		UUID buyerId,
		int totalAmount
	) {
		this.id = id;
		this.buyerId = buyerId;
		this.totalAmount = totalAmount;
	}

	public static Cart create(UUID buyerId) {
		return new Cart(UUID.randomUUID(), buyerId, 0);
	}

	public CartProduct addProduct(UUID productId) {
		if (containsProduct(productId)) {
			throw new CartException(ErrorCode.CART_ITEM_DUPLICATED);
		}

		CartProduct cartProduct = CartProduct.create(productId);
		this.cartProducts.add(cartProduct);
		cartProduct.assignCart(this);

		return cartProduct;
	}

	public void removeProduct(UUID cartProductId) {
		boolean removed = this.cartProducts.removeIf(
			cartProduct -> cartProduct.getId().equals(cartProductId)
		);

		if (removed) {
			recalculateTotalAmount();
		}
	}

	public boolean containsProduct(UUID productId) {
		return this.cartProducts.stream()
			.anyMatch(cartProduct -> cartProduct.getProductId().equals(productId));
	}

	public void removeProductsByProductIds(Collection<UUID> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return;
		}

		boolean removed = this.cartProducts.removeIf(
			cartProduct -> productIds.contains(cartProduct.getProductId())
		);

		if (removed) {
			recalculateTotalAmount();
		}
	}

	private void recalculateTotalAmount() {
		// 현재 CartProduct에는 가격 스냅샷이 없으므로 0 또는 별도 조회 필요
		// 장바구니 totalAmount를 정확히 관리하려면 CartProduct에 amountSnapshot을 두는 것이 좋음
		this.totalAmount = 0;
	}
}
