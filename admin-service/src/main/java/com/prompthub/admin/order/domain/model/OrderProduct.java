package com.prompthub.admin.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-service 소유 "order_product" 테이블의 읽기 전용 재매핑.
 * 이 3개 어드민 쿼리가 실제로 참조하는 컬럼만 매핑했다(product_id·
 * order_product_status·updated_at·downloaded 등은 매핑하지 않음).
 */
@Entity
@Table(name = "\"order_product\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderProduct {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "seller_id", columnDefinition = "uuid", nullable = false)
	private UUID sellerId;

	@Column(name = "product_title_snapshot", length = 200, nullable = false)
	private String productTitle;

	@Column(name = "product_amount_snapshot", nullable = false)
	private int productAmount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "refunded_at")
	private LocalDateTime refundedAt;
}
