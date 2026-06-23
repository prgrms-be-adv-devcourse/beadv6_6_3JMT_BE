package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.global.common.BaseEntity;
import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementBatch extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "batch_id")
	private UUID id;

	@Column(name = "batch_no", nullable = false, unique = true, length = 100)
	private String batchNo;

	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDate periodEnd;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private SettlementBatchStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false)
	private TriggerType triggerType;

	@Column(name = "failure_reason", length = 1000)
	private String failureReason;

	@Column(name = "executed_at")
	private LocalDateTime executedAt;

	private SettlementBatch(
		String batchNo,
		LocalDate periodStart,
		LocalDate periodEnd,
		TriggerType triggerType
	) {
		this.batchNo = batchNo;
		this.periodStart = periodStart;
		this.periodEnd = periodEnd;
		this.triggerType = triggerType;
		this.status = SettlementBatchStatus.PROCESSING;
	}

	public static SettlementBatch start(
		String batchNo,
		LocalDate periodStart,
		LocalDate periodEnd,
		TriggerType triggerType
	) {
		return new SettlementBatch(batchNo, periodStart, periodEnd, triggerType);
	}

	public void complete() {
		verifyProcessing();
		this.status = SettlementBatchStatus.COMPLETED;
		this.executedAt = LocalDateTime.now();
	}

	public void fail(String failureReason) {
		verifyProcessing();
		this.status = SettlementBatchStatus.FAILED;
		this.failureReason = failureReason;
		this.executedAt = LocalDateTime.now();
	}

	private void verifyProcessing() {
		if (this.status != SettlementBatchStatus.PROCESSING) {
			throw new SettlementBatchInvalidStateException(this.status);
		}
	}
}
