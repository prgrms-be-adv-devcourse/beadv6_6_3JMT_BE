package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.global.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "settlement_id")
	private UUID id;

	@Column(name = "settlement_batch_id")
	private UUID settlementBatchId;

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

	@Column(name = "failed_reason", length = 1000)
	private String failedReason;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "settlement_id", nullable = false)
	private List<SettlementDetail> details = new ArrayList<>();

	public static Settlement create(UUID settlementBatchId, UUID sellerId, SettlementPeriod period,
		List<SettlementDetail> details) {
		return new Settlement(settlementBatchId, sellerId, period, details);
	}

	private Settlement(UUID settlementBatchId, UUID sellerId, SettlementPeriod period,
		List<SettlementDetail> details) {
		Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
		Objects.requireNonNull(period, "정산 기간은 필수입니다.");
		Objects.requireNonNull(details, "정산 상세는 필수입니다.");
		this.settlementBatchId = settlementBatchId;
		this.sellerId = sellerId;
		this.periodStart = period.periodStart();
		this.periodEnd = period.periodEnd();
		this.details.addAll(details);
		this.productCount = details.size();
		this.totalAmount = sum(details, SettlementDetail::getLineAmount);
		this.feeTotalAmount = sum(details, SettlementDetail::getFeeAmount);
		this.settlementTotalAmount = sum(details, SettlementDetail::getLineSettlementAmount);
		this.refundAmount = BigDecimal.ZERO;
		this.calculatedAt = LocalDateTime.now();
	}

	private static BigDecimal sum(List<SettlementDetail> details, Function<SettlementDetail, BigDecimal> field) {
		return details.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
