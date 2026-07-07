package com.prompthub.admin.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementApplicationServiceTest {

	private static final UUID SETTLEMENT_ID = UUID.fromString("90f4f47d-3111-4787-bdb7-a29c66afd4de");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
	private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);
	private static final int PRODUCT_COUNT = 37;
	private static final BigDecimal TOTAL_AMOUNT = new BigDecimal("540000.00");
	private static final BigDecimal FEE_TOTAL_AMOUNT = new BigDecimal("81000.00");
	private static final BigDecimal SETTLEMENT_TOTAL_AMOUNT = new BigDecimal("459000.00");
	private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 1, 2, 0, 0);

	private final SettlementQueryRepository settlementQueryRepository = mock(SettlementQueryRepository.class);
	private final SettlementApplicationService service =
		new SettlementApplicationService(settlementQueryRepository);

	@Test
	void 조회_결과를_페이징_응답으로_변환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlement.getId()).thenReturn(SETTLEMENT_ID);
		when(settlement.getSellerId()).thenReturn(SELLER_ID);
		when(settlement.getPeriodStart()).thenReturn(PERIOD_START);
		when(settlement.getPeriodEnd()).thenReturn(PERIOD_END);
		when(settlement.getProductCount()).thenReturn(PRODUCT_COUNT);
		when(settlement.getTotalAmount()).thenReturn(TOTAL_AMOUNT);
		when(settlement.getFeeTotalAmount()).thenReturn(FEE_TOTAL_AMOUNT);
		when(settlement.getSettlementTotalAmount()).thenReturn(SETTLEMENT_TOTAL_AMOUNT);
		when(settlement.getCalculatedAt()).thenReturn(CALCULATED_AT);
		when(settlement.displayStatus()).thenReturn(SettlementDisplayStatus.WAITING);
		when(settlementQueryRepository.findPage(eq(SettlementDisplayStatus.WAITING), eq(0), eq(20)))
			.thenReturn(new SettlementQueryRepository.SettlementPage(List.of(settlement), 1L));

		SettlementListResponse response =
			service.getList(new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

		assertThat(response.items()).hasSize(1);
		SettlementListResponse.Item item = response.items().get(0);
		assertThat(item.settlementId()).isEqualTo(SETTLEMENT_ID);
		assertThat(item.sellerId()).isEqualTo(SELLER_ID);
		assertThat(item.sellerName()).isNull();
		assertThat(item.periodStart()).isEqualTo(PERIOD_START);
		assertThat(item.periodEnd()).isEqualTo(PERIOD_END);
		assertThat(item.productCount()).isEqualTo(PRODUCT_COUNT);
		assertThat(item.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(item.feeTotalAmount()).isEqualTo(FEE_TOTAL_AMOUNT);
		assertThat(item.settlementTotalAmount()).isEqualTo(SETTLEMENT_TOTAL_AMOUNT);
		assertThat(item.displayStatus()).isEqualTo("WAITING");
		assertThat(item.calculatedAt()).isEqualTo(CALCULATED_AT);
		assertThat(response.totalElements()).isEqualTo(1);
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
	}
}
