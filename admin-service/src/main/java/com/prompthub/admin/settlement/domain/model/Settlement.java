package com.prompthub.admin.settlement.domain.model;

import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * settlement-service 의 settlement 테이블 재매핑 읽기 모델.
 * 스키마 소유자는 settlement-service — 컬럼 정의가 바뀌면 이 매핑도 같이 맞춘다.
 */
@Entity
@Table(name = "settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

	@Id
	@Column(name = "settlement_id")
	private UUID id;

	@Column(name = "seller_id", nullable = false)
	private UUID sellerId;

	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDate periodEnd;

	@Column(name = "product_count", nullable = false)
	private int productCount;

	@Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "settlement_total_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal settlementTotalAmount;

	@Column(name = "fee_total_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal feeTotalAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "settlement_status", nullable = false)
	private SettlementStatus settlementStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "payout_status", nullable = false)
	private PayoutStatus payoutStatus;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;

	public SettlementDisplayStatus displayStatus() {
		return SettlementDisplayStatus.from(this.settlementStatus, this.payoutStatus);
	}
}
