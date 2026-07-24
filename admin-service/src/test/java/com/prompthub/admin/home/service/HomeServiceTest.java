package com.prompthub.admin.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.prompthub.admin.home.dto.HomeResult;
import com.prompthub.admin.home.repository.HomeQueryRepository;
import com.prompthub.admin.home.repository.HomeQueryRepository.DailyTransaction;
import com.prompthub.admin.home.repository.HomeQueryRepository.PendingProductPreview;
import com.prompthub.admin.home.repository.HomeQueryRepository.SettlementSummary;
import com.prompthub.admin.home.repository.HomeQueryRepository.UserSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

	@Mock
	private HomeQueryRepository repository;

	@Test
	void 홈_조회는_5개_저장소_결과를_하나의_결과로_조립한다() {
		LocalDate today = LocalDate.of(2026, 7, 24);
		Clock clock = Clock.fixed(today.atStartOfDay(ZONE_ID).toInstant().plusSeconds(3600), ZONE_ID);
		HomeService service = new HomeService(repository, clock, ZONE_ID);

		given(repository.findUserSummary(any(), any())).willReturn(new UserSummary(100L, 5L));
		given(repository.findMonthlyTransactionAmount(any(), any())).willReturn(1_000_000L);
		given(repository.findDailyTransactions(any(), any()))
			.willReturn(List.of(new DailyTransaction(today, 3L, 30_000L)));
		given(repository.findPendingApprovalSettlementSummary())
			.willReturn(new SettlementSummary(BigDecimal.valueOf(50_000), 2L));
		given(repository.findPendingProductPreview(4))
			.willReturn(new PendingProductPreview(0L, List.of()));

		HomeResult result = service.getHome();

		assertThat(result.users().totalUsers()).isEqualTo(100L);
		assertThat(result.users().todayNewUsers()).isEqualTo(5L);
		assertThat(result.transactions().monthlyTransactionAmount()).isEqualTo(1_000_000L);
		assertThat(result.transactions().recent7Days().dailyTransactions()).hasSize(7);
		assertThat(result.transactions().recent7Days().totalTransactionCount()).isEqualTo(3L);
		assertThat(result.transactions().recent7Days().totalTransactionAmount()).isEqualTo(30_000L);
		assertThat(result.settlements().pendingApprovalCount()).isEqualTo(2L);
		assertThat(result.pendingProducts().totalCount()).isEqualTo(0L);
	}

	@Test
	void 저장소에_없는_날짜는_0건_0원으로_채운다() {
		LocalDate today = LocalDate.of(2026, 7, 24);
		Clock clock = Clock.fixed(today.atStartOfDay(ZONE_ID).toInstant().plusSeconds(3600), ZONE_ID);
		HomeService service = new HomeService(repository, clock, ZONE_ID);

		given(repository.findUserSummary(any(), any())).willReturn(new UserSummary(0L, 0L));
		given(repository.findMonthlyTransactionAmount(any(), any())).willReturn(0L);
		given(repository.findDailyTransactions(any(), any())).willReturn(List.of());
		given(repository.findPendingApprovalSettlementSummary())
			.willReturn(new SettlementSummary(BigDecimal.ZERO, 0L));
		given(repository.findPendingProductPreview(4))
			.willReturn(new PendingProductPreview(0L, List.of()));

		HomeResult result = service.getHome();

		assertThat(result.transactions().recent7Days().dailyTransactions())
			.allSatisfy(day -> {
				assertThat(day.transactionCount()).isZero();
				assertThat(day.transactionAmount()).isZero();
			});
	}
}
