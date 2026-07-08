package com.prompthub.admin.settlement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * settlement_source_line 재매핑 — cancel 시 라인 해제(settlement_id → null)에 필요한 최소 컬럼만 매핑.
 * 스키마 소유자는 settlement-service. admin 은 소스 라인을 생성하지 않는다.
 */
@Entity
@Table(name = "settlement_source_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementSourceLine {

	@Id
	@Column(name = "settlement_source_line_id")
	private UUID id;

	@Column(name = "settlement_id")
	private UUID settlementId;

	public void release(UUID settlementId) {
		if (this.settlementId != null && this.settlementId.equals(settlementId)) {
			this.settlementId = null;
		}
	}
}
