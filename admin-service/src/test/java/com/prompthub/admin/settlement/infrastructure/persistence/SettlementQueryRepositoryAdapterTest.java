package com.prompthub.admin.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@DataJpaTest
@Import(SettlementQueryRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/settlements.sql")
class SettlementQueryRepositoryAdapterTest {

	@Autowired
	private SettlementQueryRepository settlementQueryRepository;

	@Test
	void 상태_필터_없이_전체를_계산시각_내림차순으로_페이징한다() {
		SettlementQueryRepository.SettlementPage result =
			settlementQueryRepository.findPage(null, 0, 2);

		assertThat(result.totalElements()).isEqualTo(3);
		assertThat(result.content()).hasSize(2);
		assertThat(result.content().get(0).getCalculatedAt())
			.isAfterOrEqualTo(result.content().get(1).getCalculatedAt());
	}

	@Test
	void 표시상태로_필터링한다() {
		SettlementQueryRepository.SettlementPage result =
			settlementQueryRepository.findPage(SettlementDisplayStatus.PAID, 0, 20);

		assertThat(result.totalElements()).isEqualTo(1);
		assertThat(result.content().get(0).displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
	}
}
