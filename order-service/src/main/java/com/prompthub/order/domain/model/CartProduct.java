package com.prompthub.order.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "cart_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartProduct {

	@Id
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cart_id", nullable = false)
	private Cart cart;

	@Column(name = "product_id", columnDefinition = "char(36)", nullable = false)
	private UUID productId;

	@Column(name = "added_at", nullable = false)
	private LocalDateTime addedAt;

	protected void assignCart(Cart cart) {
		this.cart = cart;
	}
}