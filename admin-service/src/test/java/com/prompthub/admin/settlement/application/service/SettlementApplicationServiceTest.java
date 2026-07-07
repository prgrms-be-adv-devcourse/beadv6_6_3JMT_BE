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
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementApplicationServiceTest {

	private final SettlementQueryRepository settlementQueryRepository = mock(SettlementQueryRepository.class);
	private final SettlementApplicationService service =
		new SettlementApplicationService(settlementQueryRepository);

	@Test
	void 조회_결과를_페이징_응답으로_변환한다() {
		Settlement settlement = mock(Settlement.class);
		when(settlement.displayStatus()).thenReturn(SettlementDisplayStatus.WAITING);
		when(settlementQueryRepository.findPage(eq(SettlementDisplayStatus.WAITING), eq(0), eq(20)))
			.thenReturn(new SettlementQueryRepository.SettlementPage(List.of(settlement), 1L));

		SettlementListResponse response =
			service.getList(new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().get(0).displayStatus()).isEqualTo("WAITING");
		assertThat(response.items().get(0).sellerName()).isNull();
		assertThat(response.totalElements()).isEqualTo(1);
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
	}
}
