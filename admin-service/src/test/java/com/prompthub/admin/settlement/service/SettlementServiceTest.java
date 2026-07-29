package com.prompthub.admin.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.dto.SettlementListQuery;
import com.prompthub.admin.settlement.dto.SettlementWeeklyListQuery;
import com.prompthub.admin.settlement.entity.Settlement;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.SettlementSourceLine;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobAlreadyExistsException;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobClient;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyKey;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyPage;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementQueryRepository.DeliveryPage;
import com.prompthub.admin.settlement.repository.SettlementRepository;
import com.prompthub.admin.settlement.repository.SettlementSourceRepository;
import com.prompthub.admin.settlement.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.repository.SettlementWeeklyQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementWeeklyQueryRepository.WeeklyPage;
import com.prompthub.admin.settlement.repository.SettlementWeeklyStatusCount;
import com.prompthub.admin.settlement.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliveryListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliverySummaryResponse;
import com.prompthub.admin.settlement.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.dto.response.SettlementSummaryResponse;
import com.prompthub.admin.settlement.dto.response.SettlementWeeklyListResponse;
import com.prompthub.admin.user.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementServiceTest {

	private static final UUID SETTLEMENT_ID = UUID.fromString("90f4f47d-3111-4787-bdb7-a29c66afd4de");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID DELIVERY_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000663");

	private final SettlementQueryRepository settlementQueryRepository = mock(SettlementQueryRepository.class);
	private final SettlementMonthlyQueryRepository monthlyQueryRepository =
		mock(SettlementMonthlyQueryRepository.class);
	private final SettlementWeeklyQueryRepository weeklyQueryRepository =
		mock(SettlementWeeklyQueryRepository.class);
	private final UserService userService = mock(UserService.class);
	private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
	private final SettlementSourceRepository settlementSourceRepository = mock(SettlementSourceRepository.class);
	private final SettlementDeliveryRetryJobClient retryJobClient =
		mock(SettlementDeliveryRetryJobClient.class);
	private final SettlementService service = new SettlementService(
		settlementQueryRepository, monthlyQueryRepository, weeklyQueryRepository, userService,
		settlementRepository, settlementSourceRepository, retryJobClient);

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
		when(userService.findNamesByIds(List.of(SELLER_ID)))
			.thenReturn(Map.of(SELLER_ID, "프롬프트 상점"));

		SettlementListResponse response = service.getList(
			new SettlementListQuery(null, null, 0, 20));

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.sellerId()).isEqualTo(SELLER_ID);
			assertThat(item.sellerName()).isEqualTo("프롬프트 상점");
			assertThat(item.settlementMonth()).isEqualTo("2026-07");
			assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
		});
		verify(userService).findNamesByIds(List.of(SELLER_ID));
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
		when(userService.findNamesByIds(List.of(SELLER_ID)))
			.thenReturn(Map.of());

		SettlementListResponse response = service.getList(
			new SettlementListQuery(null, null, 0, 20));

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.sellerId()).isEqualTo(SELLER_ID);
			assertThat(item.sellerName()).isNull();
		});
	}

	@Test
	void 주간목록은_상태필터를_행에_적용하고_전체상태건수를_함께_반환한다() {
		YearMonth month = YearMonth.of(2026, 7);
		when(weeklyQueryRepository.findWeeklyPage(
			SettlementDisplayStatus.APPROVED, month, 0, 20))
			.thenReturn(new WeeklyPage(List.of(), 0));
		when(weeklyQueryRepository.findStatusCounts(month)).thenReturn(List.of(
			new SettlementWeeklyStatusCount(SettlementDisplayStatus.APPROVED, 3)));

		SettlementWeeklyListResponse response = service.getWeeklyList(
			new SettlementWeeklyListQuery(SettlementDisplayStatus.APPROVED, month, 0, 20));

		assertThat(response.items()).isEmpty();
		assertThat(response.statusCounts())
			.filteredOn(count -> count.status().equals("APPROVED"))
			.singleElement()
			.extracting(SettlementWeeklyListResponse.StatusCount::count)
			.isEqualTo(3L);
		assertThat(response.statusCounts()).hasSize(SettlementDisplayStatus.values().length);
		verify(weeklyQueryRepository).findWeeklyPage(
			SettlementDisplayStatus.APPROVED, month, 0, 20);
		verify(weeklyQueryRepository).findStatusCounts(month);
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

	@Test
	void 전달실패건은_실행중인Job이_없으면_RETRY액션을_제공한다() {
		SettlementDelivery delivery = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
		SettlementDeliveryListQuery query =
			new SettlementDeliveryListQuery(null, true, null, 0, 20);
		when(settlementQueryRepository.findDeliveryPage(query))
			.thenReturn(new DeliveryPage(List.of(delivery), 1L));
		when(retryJobClient.findActiveDeliveryIds()).thenReturn(Set.of());

		SettlementDeliveryListResponse response = service.getDeliveryList(query);

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.settlementDeliveryId()).isEqualTo(DELIVERY_ID);
			assertThat(item.retryInProgress()).isFalse();
			assertThat(item.availableActions()).containsExactly("RETRY");
		});
		assertThat(response.totalElements()).isEqualTo(1L);
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
	}

	@Test
	void 불일치건과_실행중인_전달실패건은_RETRY액션을_제공하지_않는다() {
		SettlementDelivery mismatch = delivery(SettlementDeliveryStatus.MISMATCH);
		SettlementDelivery failed = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
		SettlementDeliveryListQuery query =
			new SettlementDeliveryListQuery(null, true, null, 0, 20);
		when(settlementQueryRepository.findDeliveryPage(query))
			.thenReturn(new DeliveryPage(List.of(mismatch, failed), 2L));
		when(retryJobClient.findActiveDeliveryIds()).thenReturn(Set.of(DELIVERY_ID));

		SettlementDeliveryListResponse response = service.getDeliveryList(query);

		assertThat(response.items())
			.allSatisfy(item -> assertThat(item.availableActions()).isEmpty());
		assertThat(response.items())
			.allSatisfy(item -> assertThat(item.retryInProgress()).isTrue());
	}

	@Test
	void 전달상태와_실행중Job건수를_요약한다() {
		when(settlementQueryRepository.countDeliveryByStatus()).thenReturn(Map.of(
			SettlementDeliveryStatus.CALCULATED, 2L,
			SettlementDeliveryStatus.RECONCILED, 8L,
			SettlementDeliveryStatus.DELIVERY_FAILED, 3L,
			SettlementDeliveryStatus.MISMATCH, 1L));
		when(retryJobClient.findActiveDeliveryIds())
			.thenReturn(Set.of(DELIVERY_ID));

		SettlementDeliverySummaryResponse response = service.getDeliverySummary();

		assertThat(response.calculatedCount()).isEqualTo(2L);
		assertThat(response.reconciledCount()).isEqualTo(8L);
		assertThat(response.deliveryFailedCount()).isEqualTo(3L);
		assertThat(response.mismatchCount()).isEqualTo(1L);
		assertThat(response.retryInProgressCount()).isEqualTo(1L);
	}

	@Test
	void 전달실패건의_단발성_재전송Job을_요청한다() {
		SettlementDelivery delivery = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
		when(settlementQueryRepository.findDeliveryById(DELIVERY_ID))
			.thenReturn(Optional.of(delivery));
		when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(false);

		service.retryDelivery(DELIVERY_ID);

		verify(retryJobClient).launch(DELIVERY_ID, 3);
	}

	@Test
	void 존재하지_않는_전달건은_404예외로_변환한다() {
		when(settlementQueryRepository.findDeliveryById(DELIVERY_ID))
			.thenReturn(Optional.empty());

		AdminException exception = catchThrowableOfType(
			AdminException.class,
			() -> service.retryDelivery(DELIVERY_ID));

		assertThat(exception.getErrorCode())
			.isEqualTo(AdminErrorCode.SETTLEMENT_DELIVERY_NOT_FOUND);
		verifyNoInteractions(retryJobClient);
	}

	@Test
	void 전달실패가_아니거나_이미실행중이면_409예외로_변환한다() {
		SettlementDelivery mismatch = delivery(SettlementDeliveryStatus.MISMATCH);
		when(settlementQueryRepository.findDeliveryById(DELIVERY_ID))
			.thenReturn(Optional.of(mismatch));

		AdminException invalidState = catchThrowableOfType(
			AdminException.class,
			() -> service.retryDelivery(DELIVERY_ID));

		assertThat(invalidState.getErrorCode())
			.isEqualTo(
				AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_NOT_ALLOWED);

		SettlementDelivery failed = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
		when(settlementQueryRepository.findDeliveryById(DELIVERY_ID))
			.thenReturn(Optional.of(failed));
		when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(true);

		AdminException activeRetry = catchThrowableOfType(
			AdminException.class,
			() -> service.retryDelivery(DELIVERY_ID));

		assertThat(activeRetry.getErrorCode())
			.isEqualTo(
				AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
		verify(retryJobClient, never()).launch(DELIVERY_ID, 3);
	}

	@Test
	void 조회와_생성사이에_같은Job이_생성돼도_409예외로_변환한다() {
		SettlementDelivery failed = delivery(
			SettlementDeliveryStatus.DELIVERY_FAILED);
		when(settlementQueryRepository.findDeliveryById(DELIVERY_ID))
			.thenReturn(Optional.of(failed));
		when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(false);
		org.mockito.Mockito.doThrow(
				new SettlementDeliveryRetryJobAlreadyExistsException(
					"already exists",
					new RuntimeException()))
			.when(retryJobClient)
			.launch(DELIVERY_ID, 3);

		AdminException exception = catchThrowableOfType(
			AdminException.class,
			() -> service.retryDelivery(DELIVERY_ID));

		assertThat(exception.getErrorCode())
			.isEqualTo(
				AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
	}

	private SettlementDelivery delivery(SettlementDeliveryStatus status) {
		SettlementDelivery delivery = mock(SettlementDelivery.class);
		when(delivery.getSettlementDeliveryId()).thenReturn(DELIVERY_ID);
		when(delivery.getSettlementId()).thenReturn(UUID.randomUUID());
		when(delivery.getDeliveryRequestId()).thenReturn(UUID.randomUUID());
		when(delivery.getStatus()).thenReturn(status);
		when(delivery.canRetry())
			.thenReturn(status == SettlementDeliveryStatus.DELIVERY_FAILED);
		when(delivery.getAttemptCount()).thenReturn(3);
		when(delivery.getStatusReason()).thenReturn("gRPC UNAVAILABLE: attempts=3");
		when(delivery.getFirstAttemptAt()).thenReturn(
			LocalDateTime.of(2026, 7, 29, 10, 0));
		when(delivery.getLastAttemptAt()).thenReturn(
			LocalDateTime.of(2026, 7, 29, 10, 0, 4));
		return delivery;
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}
}
