package com.prompthub.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCreatedMessageTest {

	@Test
	@DisplayName("Settlement의 식별자·기간·금액·건수를 스냅샷으로 매핑한다")
	void from_mapsSnapshot() {
		UUID sellerId = UUID.randomUUID();
		UUID batchId = UUID.randomUUID();
		SettlementDetail detail = SettlementDetail.sale(
				UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"),
				LocalDateTime.of(2026, 6, 15, 10, 0));
		Settlement settlement = Settlement.create(batchId, sellerId, YearMonth.of(2026, 6), List.of(detail));
		UUID settlementId = UUID.randomUUID();
		ReflectionTestUtils.setField(settlement, "id", settlementId);

		SettlementCreatedMessage message = SettlementCreatedMessage.from(settlement);

		assertThat(message.settlementId()).isEqualTo(settlementId);
		assertThat(message.sellerId()).isEqualTo(sellerId);
		assertThat(message.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
		assertThat(message.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
		assertThat(message.productCount()).isEqualTo(1);
		assertThat(message.totalAmount()).isEqualByComparingTo("100.00");
		assertThat(message.feeTotalAmount()).isEqualByComparingTo("15.00");
		assertThat(message.settlementTotalAmount()).isEqualByComparingTo("85.00");
		assertThat(message.refundAmount()).isEqualByComparingTo("0");
	}
}
