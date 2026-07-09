package com.prompthub.admin.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
	private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
	private final SettlementSourceRepository settlementSourceRepository = mock(SettlementSourceRepository.class);
	private final SettlementApplicationService service = new SettlementApplicationService(
		settlementQueryRepository, settlementRepository, settlementSourceRepository);

	@Test
	void 조회_결과를_페이징_응답으로_변환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlement.getSettlementId()).thenReturn(SETTLEMENT_ID);
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

	@Test
	void 요약_카드를_상태별로_집계한다() {
		when(settlementQueryRepository.aggregateByStatus()).thenReturn(List.of(
			new SettlementStatusAggregate(
				SettlementDisplayStatus.WAITING, new BigDecimal("100000.00"), 2L),
			new SettlementStatusAggregate(
				SettlementDisplayStatus.APPROVED, new BigDecimal("200000.00"), 3L),
			new SettlementStatusAggregate(
				SettlementDisplayStatus.PAYOUT_ON_HOLD, new BigDecimal("50000.00"), 1L),
			new SettlementStatusAggregate(
				SettlementDisplayStatus.PAID, new BigDecimal("300000.00"), 4L)
		));

		SettlementSummaryResponse response = service.getSummary();

		assertThat(response.cards()).hasSize(4);
		SettlementSummaryResponse.Card waiting = response.cards().get(0);
		assertThat(waiting.status()).isEqualTo("WAITING");
		assertThat(waiting.totalAmount()).isEqualByComparingTo("100000.00");
		assertThat(waiting.count()).isEqualTo(2L);

		SettlementSummaryResponse.Card approved = response.cards().get(1);
		assertThat(approved.status()).isEqualTo("APPROVED");
		assertThat(approved.totalAmount()).isEqualByComparingTo("200000.00");
		assertThat(approved.count()).isEqualTo(3L);

		SettlementSummaryResponse.Card payoutOnHold = response.cards().get(2);
		assertThat(payoutOnHold.status()).isEqualTo("PAYOUT_ON_HOLD");
		assertThat(payoutOnHold.totalAmount()).isEqualByComparingTo("50000.00");
		assertThat(payoutOnHold.count()).isEqualTo(1L);

		SettlementSummaryResponse.Card paid = response.cards().get(3);
		assertThat(paid.status()).isEqualTo("PAID");
		assertThat(paid.totalAmount()).isEqualByComparingTo("300000.00");
		assertThat(paid.count()).isEqualTo(4L);
	}

	@Test
	void 정산을_승인하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.approve(SETTLEMENT_ID);

		verify(settlement).approve(any(LocalDateTime.class));
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산을_보류하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.hold(SETTLEMENT_ID);

		verify(settlement).hold();
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산_보류를_해제하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.releaseHold(SETTLEMENT_ID);

		verify(settlement).releaseHold();
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산을_지급처리하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.payout(SETTLEMENT_ID);

		verify(settlement).payout(any(LocalDateTime.class));
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산_지급을_보류하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.payoutHold(SETTLEMENT_ID);

		verify(settlement).payoutHold();
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산_지급_보류를_해제하면_상태변경_응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

		SettlementStatusResponse response = service.releasePayoutHold(SETTLEMENT_ID);

		verify(settlement).releasePayoutHold();
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 정산을_취소하면_소스라인을_해제하고_취소응답을_반환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
		when(settlement.displayStatus()).thenReturn(SettlementDisplayStatus.CANCELLED);
		SettlementSourceLine line1 = mock(SettlementSourceLine.class);
		SettlementSourceLine line2 = mock(SettlementSourceLine.class);
		when(settlementSourceRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(line1, line2));

		SettlementResponse response = service.cancel(SETTLEMENT_ID);

		verify(settlement).cancel(any(LocalDateTime.class));
		verify(line1).release(SETTLEMENT_ID);
		verify(line2).release(SETTLEMENT_ID);
		verify(settlementRepository).save(settlement);
		assertThat(response).isNotNull();
	}

	@Test
	void 존재하지_않는_정산을_승인하려_하면_예외가_발생한다() {
		when(settlementRepository.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.empty());

		AdminException exception =
			catchThrowableOfType(AdminException.class, () -> service.approve(SETTLEMENT_ID));

		assertThat(exception.getErrorCode()).isEqualTo(AdminErrorCode.SETTLEMENT_NOT_FOUND);
	}
}
