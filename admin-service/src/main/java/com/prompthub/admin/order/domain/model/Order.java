package com.prompthub.admin.order.domain.model;

import com.prompthub.admin.order.domain.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * order-service 소유 "order" 테이블의 읽기 전용 재매핑.
 * admin-service는 이 테이블에 쓰기 작업을 하지 않는다 — 컬럼 정의가 바뀌면
 * order-service의 Order 엔티티에 맞춰 같이 수정한다. 이 3개 어드민 쿼리가
 * 실제로 참조하는 컬럼만 매핑했다.
 */
@Entity
@Table(name = "\"order\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "total_order_amount", nullable = false)
	private int totalOrderAmount;

	@Column(name = "order_number", nullable = false, length = 30)
	private String orderNumber;

	@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
	private UUID buyerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_status", length = 20, nullable = false)
	private OrderStatus orderStatus;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "created_at", updatable = false)
	private LocalDateTime createdAt;

	@OneToMany(mappedBy = "order")
	private final List<OrderProduct> orderProducts = new ArrayList<>();
}
