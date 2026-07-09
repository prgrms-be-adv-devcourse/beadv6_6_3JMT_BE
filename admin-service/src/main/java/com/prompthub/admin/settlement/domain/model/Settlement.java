package com.prompthub.admin.settlement.domain.model;

import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyPaidException;
import com.prompthub.admin.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.admin.global.common.BaseEntity;
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
 * settlement-service 의 settlement 테이블 재매핑 쓰기 모델(상태 전이).
 * 스키마 소유자는 settlement-service — 컬럼 정의가 바뀌면 이 매핑도 같이 맞춘다.
 */
@Entity
@Table(name = "settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

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

	@Column(name = "failed_reason", length = 1000)
	private String failedReason;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	@Column(name = "paid_at")
	private LocalDateTime paidAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(name = "payout_reference", length = 100)
	private String payoutReference;

	public SettlementDisplayStatus displayStatus() {
		return SettlementDisplayStatus.from(this.settlementStatus, this.payoutStatus);
	}

	public void approve(LocalDateTime confirmedAt) {
		if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
			throw new SettlementInvalidStateException("approve", this.settlementStatus, this.payoutStatus);
		}
		this.settlementStatus = SettlementStatus.APPROVED;
		this.payoutStatus = PayoutStatus.READY;
		this.confirmedAt = confirmedAt;
	}

	public void hold() {
		if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
			throw new SettlementInvalidStateException("hold", this.settlementStatus, this.payoutStatus);
		}
		this.settlementStatus = SettlementStatus.SETTLEMENT_ON_HOLD;
	}

	public void releaseHold() {
		if (this.settlementStatus != SettlementStatus.SETTLEMENT_ON_HOLD) {
			throw new SettlementInvalidStateException("releaseHold", this.settlementStatus, this.payoutStatus);
		}
		this.settlementStatus = SettlementStatus.PENDING_APPROVAL;
	}

	public void payout(LocalDateTime paidAt) {
		if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.PAYOUT_REQUESTED) {
			throw new SettlementInvalidStateException("payout", this.settlementStatus, this.payoutStatus);
		}
		this.payoutStatus = PayoutStatus.PAID;
		this.paidAt = paidAt;
	}

	public void payoutHold() {
		if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.PAYOUT_REQUESTED) {
			throw new SettlementInvalidStateException("payoutHold", this.settlementStatus, this.payoutStatus);
		}
		this.payoutStatus = PayoutStatus.PAYOUT_ON_HOLD;
	}

	public void releasePayoutHold() {
		if (this.settlementStatus != SettlementStatus.APPROVED
			|| this.payoutStatus != PayoutStatus.PAYOUT_ON_HOLD) {
			throw new SettlementInvalidStateException("releasePayoutHold", this.settlementStatus, this.payoutStatus);
		}
		this.payoutStatus = PayoutStatus.PAYOUT_REQUESTED;
	}

	public void cancel(LocalDateTime canceledAt) {
		if (this.payoutStatus == PayoutStatus.PAID) {
			throw new SettlementAlreadyPaidException(this.id);
		}
		if (this.settlementStatus == SettlementStatus.CANCELLED) {
			throw new SettlementAlreadyCancelledException(this.id);
		}
		this.settlementStatus = SettlementStatus.CANCELLED;
		this.canceledAt = canceledAt;
	}
}
