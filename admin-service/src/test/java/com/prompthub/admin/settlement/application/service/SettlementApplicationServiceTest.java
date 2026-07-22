package com.prompthub.admin.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.application.port.SellerNameQueryPort;
import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyKey;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyPage;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementApplicationServiceTest {

	private static final UUID SETTLEMENT_ID = UUID.fromString("90f4f47d-3111-4787-bdb7-a29c66afd4de");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private final SettlementQueryRepository settlementQueryRepository = mock(SettlementQueryRepository.class);
	private final SettlementMonthlyQueryRepository monthlyQueryRepository =
		mock(SettlementMonthlyQueryRepository.class);
	private final SellerNameQueryPort sellerNameQueryPort = mock(SellerNameQueryPort.class);
	private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
	private final SettlementSourceRepository settlementSourceRepository = mock(SettlementSourceRepository.class);
	private final SettlementApplicationService service = new SettlementApplicationService(
		settlementQueryRepository, monthlyQueryRepository, sellerNameQueryPort,
		settlementRepository, settlementSourceRepository);

	@Test
	void 월별목록은_상태건수와_판매자명을_한번에_조립한다() {
		MonthlyKey key = new MonthlyKey(SELLER_ID, YearMonth.of(2026, 7));
		MonthlyAggregate aggregate = new MonthlyAggregate(
			key, 3, 2, 22,
			bd("2200000"), bd("330000"), bd("100000"), bd("1770000"));
		when(monthlyQueryRepository.findMonthlyPage(null, null, 0, 20))
			.thenReturn(new MonthlyPage(List.of(aggregate), 1));
		when(monthlyQueryRepository.findStatusCounts(List.of(key)))
			.thenReturn(List.of(new MonthlyStatusCount(
				key, SettlementDisplayStatus.APPROVED, 1)));
		when(sellerNameQueryPort.findNamesBySellerIds(List.of(SELLER_ID)))
			.thenReturn(Map.of(SELLER_ID, "프롬프트 상점"));

		SettlementListResponse response = service.getList(
			new SettlementListQuery(null, null, 0, 20));

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.sellerId()).isEqualTo(SELLER_ID);
			assertThat(item.sellerName()).isEqualTo("프롬프트 상점");
			assertThat(item.settlementMonth()).isEqualTo("2026-07");
			assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
		});
		verify(sellerNameQueryPort).findNamesBySellerIds(List.of(SELLER_ID));
	}

	@Test
	void 판매자명이_없어도_월별그룹을_null이름으로_유지한다() {
		MonthlyKey key = new MonthlyKey(SELLER_ID, YearMonth.of(2026, 7));
		MonthlyAggregate aggregate = new MonthlyAggregate(
			key, 1, 1, 1,
			bd("100"), bd("15"), bd("0"), bd("85"));
		when(monthlyQueryRepository.findMonthlyPage(null, null, 0, 20))
			.thenReturn(new MonthlyPage(List.of(aggregate), 1));
		when(monthlyQueryRepository.findStatusCounts(List.of(key))).thenReturn(List.of());
		when(sellerNameQueryPort.findNamesBySellerIds(List.of(SELLER_ID)))
			.thenReturn(Map.of());

		SettlementListResponse response = service.getList(
			new SettlementListQuery(null, null, 0, 20));

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.sellerId()).isEqualTo(SELLER_ID);
			assertThat(item.sellerName()).isNull();
		});
	}

	@Test
	void 상세_판매자월이_없으면_404다() {
		YearMonth month = YearMonth.of(2026, 7);
		when(monthlyQueryRepository.findMonthlyAggregate(SELLER_ID, month))
			.thenReturn(Optional.empty());

		AdminException exception = catchThrowableOfType(
			AdminException.class, () -> service.getDetail(SELLER_ID, month));

		assertThat(exception.getErrorCode()).isEqualTo(AdminErrorCode.SETTLEMENT_NOT_FOUND);
	}

	@Test
	void 요약은_선택월을_저장소에_전달하고_기존카드버킷을_유지한다() {
		YearMonth month = YearMonth.of(2026, 7);
		when(settlementQueryRepository.aggregateByStatus(month)).thenReturn(List.of(
			new SettlementStatusAggregate(
				SettlementDisplayStatus.APPROVAL_ON_HOLD, bd("100"), 1L),
			new SettlementStatusAggregate(
				SettlementDisplayStatus.PAYOUT_REQUESTED, bd("200"), 2L)
		));

		SettlementSummaryResponse response = service.getSummary(month);

		assertThat(response.cards()).hasSize(4);
		SettlementSummaryResponse.Card waiting = response.cards().get(0);
		assertThat(waiting.status()).isEqualTo("WAITING");
		assertThat(waiting.totalAmount()).isEqualByComparingTo("100");
		assertThat(waiting.count()).isEqualTo(1L);

		SettlementSummaryResponse.Card approved = response.cards().get(1);
		assertThat(approved.status()).isEqualTo("APPROVED");
		assertThat(approved.totalAmount()).isEqualByComparingTo("200");
		assertThat(approved.count()).isEqualTo(2L);
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

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}
}
