package com.prompthub.order.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "cart")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

	@Id
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@Column(name = "buyer_id", columnDefinition = "char(36)", nullable = false)
	private UUID buyerId;

	@Column(name = "total_amount", nullable = false)
	private int totalAmount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@OneToMany(
		mappedBy = "cart",
		cascade = CascadeType.ALL,
		orphanRemoval = true
	)
	private List<CartProduct> cartProducts = new ArrayList<>();

	public void removeProductsByProductIds(Collection<UUID> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return;
		}

		boolean removed = this.cartProducts.removeIf(
			cartProduct -> productIds.contains(cartProduct.getProductId())
		);

		if (removed) {
			this.updatedAt = LocalDateTime.now();
			recalculateTotalAmount();
		}
	}

	private void recalculateTotalAmount() {
		// 현재 CartProduct에는 가격 스냅샷이 없으므로 0 또는 별도 조회 필요
		// 장바구니 totalAmount를 정확히 관리하려면 CartProduct에 amountSnapshot을 두는 것이 좋음
		this.totalAmount = 0;
	}
}