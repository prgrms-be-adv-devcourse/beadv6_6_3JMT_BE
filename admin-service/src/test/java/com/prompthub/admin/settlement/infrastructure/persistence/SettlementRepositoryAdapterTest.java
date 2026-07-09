package com.prompthub.admin.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@DataJpaTest
@Import({SettlementRepositoryAdapter.class, SettlementQueryRepositoryAdapter.class})
@ActiveProfiles("test")
@Sql("/sql/settlements.sql")
class SettlementRepositoryAdapterTest {

	private static final UUID SETTLEMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Autowired
	private SettlementRepository settlementRepository;

	@Autowired
	private SettlementQueryRepository settlementQueryRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void settlementId로_정산건을_조회한다() {
		Optional<Settlement> found = settlementRepository.findBySettlementId(SETTLEMENT_ID);

		assertThat(found).isPresent();
		assertThat(found.get().getSellerId()).isEqualTo(SELLER_ID);
	}

	@Test
	void 표시상태별로_합계금액과_건수를_집계한다() {
		List<SettlementStatusAggregate> result = settlementQueryRepository.aggregateByStatus();

		assertThat(result)
			.extracting(SettlementStatusAggregate::status,
				SettlementStatusAggregate::sumSettlementTotal,
				SettlementStatusAggregate::count)
			.containsExactlyInAnyOrder(
				tuple(SettlementDisplayStatus.WAITING, new BigDecimal("459000.00"), 1L),
				tuple(SettlementDisplayStatus.PAID, new BigDecimal("765000.00"), 1L),
				tuple(SettlementDisplayStatus.APPROVED, new BigDecimal("85000.00"), 1L));
	}

	@Test
	void 승인_후_저장하면_재조회시_승인상태가_유지된다() {
		Settlement settlement = settlementRepository.findBySettlementId(SETTLEMENT_ID).orElseThrow();

		settlement.approve(LocalDateTime.of(2026, 7, 7, 10, 0));
		settlementRepository.save(settlement);

		entityManager.flush();
		entityManager.clear();

		Settlement reloaded = settlementRepository.findBySettlementId(SETTLEMENT_ID).orElseThrow();
		assertThat(reloaded.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
		assertThat(reloaded.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 7, 7, 10, 0));
	}
}
