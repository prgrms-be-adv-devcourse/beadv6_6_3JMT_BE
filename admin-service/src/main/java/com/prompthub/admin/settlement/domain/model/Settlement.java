package com.prompthub.admin.settlement.domain.model;

import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyPaidException;
import com.prompthub.admin.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
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
 * user-service 의 seller_settlement(운영 단일 진실) 재매핑 쓰기 모델(상태 전이).
 * 스키마·상태 전이 규칙의 소유자는 user-service SellerSettlement — 컬럼 정의나 전이 가드가
 * 바뀌면 이 매핑도 같이 맞춘다. 어드민은 이 행을 생성하지 않고(배치 seed) 조회·상태 전이만 한다.
 */
@Entity
@Table(name = "seller_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

	@Id
	@Column(name = "seller_settlement_id")
	private UUID sellerSettlementId;

	@Column(name = "settlement_id", nullable = false, unique = true)
	private UUID settlementId;

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

	@Column(name = "refund_amount", precision = 12, scale = 2)
	private BigDecimal refundAmount;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private SettlementDisplayStatus status;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "payout_requested_at")
	private LocalDateTime payoutRequestedAt;

	@Column(name = "paid_at")
	private LocalDateTime paidAt;

	@Column(name = "cancelled_at")
	private LocalDateTime cancelledAt;

	public SettlementDisplayStatus displayStatus() {
		return this.status;
	}

	public void approve(LocalDateTime approvedAt) {
		requireStatus(SettlementDisplayStatus.WAITING, "approve");
		this.status = SettlementDisplayStatus.APPROVED;
		this.approvedAt = approvedAt;
	}

	public void hold() {
		requireStatus(SettlementDisplayStatus.WAITING, "hold");
		this.status = SettlementDisplayStatus.APPROVAL_ON_HOLD;
	}

	public void releaseHold() {
		requireStatus(SettlementDisplayStatus.APPROVAL_ON_HOLD, "releaseHold");
		this.status = SettlementDisplayStatus.WAITING;
	}

	public void payout(LocalDateTime paidAt) {
		requireStatus(SettlementDisplayStatus.PAYOUT_REQUESTED, "payout");
		this.status = SettlementDisplayStatus.PAID;
		this.paidAt = paidAt;
	}

	public void payoutHold() {
		requireStatus(SettlementDisplayStatus.PAYOUT_REQUESTED, "payoutHold");
		this.status = SettlementDisplayStatus.PAYOUT_ON_HOLD;
	}

	public void releasePayoutHold() {
		requireStatus(SettlementDisplayStatus.PAYOUT_ON_HOLD, "releasePayoutHold");
		this.status = SettlementDisplayStatus.PAYOUT_REQUESTED;
	}

	public void cancel(LocalDateTime cancelledAt) {
		if (this.status == SettlementDisplayStatus.PAID) {
			throw new SettlementAlreadyPaidException(this.settlementId);
		}
		if (this.status == SettlementDisplayStatus.CANCELLED) {
			throw new SettlementAlreadyCancelledException(this.settlementId);
		}
		this.status = SettlementDisplayStatus.CANCELLED;
		this.cancelledAt = cancelledAt;
	}

	private void requireStatus(SettlementDisplayStatus expected, String action) {
		if (this.status != expected) {
			throw new SettlementInvalidStateException(action, this.status);
		}
	}
}
