package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.global.common.BaseEntity;
import com.prompthub.settlement.domain.exception.SettlementSourceLineAlreadySettledException;
import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement_source_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementSourceLine extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "settlement_source_line_id")
	private UUID id;

	@Column(name = "event_id", nullable = false, unique = true)
	private UUID eventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "line_type", nullable = false, length = 30)
	private SettlementSourceLineType lineType;

	@Column(name = "order_id")
	private UUID orderId;

	@Column(name = "order_product_id", nullable = false)
	private UUID orderProductId;

	@Column(name = "seller_id", nullable = false)
	private UUID sellerId;

	@Column(name = "line_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal lineAmount;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "settlement_id")
	private UUID settlementId;

	public static SettlementSourceLine paid(
		UUID eventId,
		UUID orderId,
		UUID orderProductId,
		UUID sellerId,
		BigDecimal amount,
		LocalDateTime occurredAt
	) {
		return new SettlementSourceLine(
			eventId,
			SettlementSourceLineType.PAID,
			orderId, orderProductId,
			sellerId,
			requirePositive(amount),
			occurredAt
		);
	}

	public static SettlementSourceLine refunded(
		UUID eventId,
		UUID orderId,
		UUID orderProductId,
		UUID sellerId,
		BigDecimal amount,
		LocalDateTime occurredAt
	) {
		return new SettlementSourceLine(
			eventId,
			SettlementSourceLineType.REFUND,
			orderId,
			orderProductId,
			sellerId,
			requirePositive(amount),
			occurredAt
		);
	}

	private SettlementSourceLine(UUID eventId, SettlementSourceLineType lineType, UUID orderId,
		UUID orderProductId, UUID sellerId, BigDecimal lineAmount,
		LocalDateTime occurredAt) {
		this.eventId = require(eventId, "eventId");
		this.lineType = lineType;
		this.orderId = orderId;
		this.orderProductId = require(orderProductId, "orderProductId");
		this.sellerId = require(sellerId, "sellerId");
		this.lineAmount = lineAmount;
		this.occurredAt = require(occurredAt, "occurredAt");
	}

	public void markSettled(UUID settlementId) {
		if (isSettled()) {
			throw new SettlementSourceLineAlreadySettledException(this.settlementId);
		}
		this.settlementId = require(settlementId, "settlementId");
	}

	public void release(UUID settlementId) {
		if (this.settlementId != null && this.settlementId.equals(settlementId)) {
			this.settlementId = null;
		}
	}

	public boolean isSettled() {
		return this.settlementId != null;
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new IllegalArgumentException(name + "은(는) 필수입니다.");
		}
		return value;
	}

	private static BigDecimal requirePositive(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("amount는(은) 필수입니다.");
		}
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("amount는 0보다 커야 합니다. amount=" + amount);
		}
		return amount;
	}
}
