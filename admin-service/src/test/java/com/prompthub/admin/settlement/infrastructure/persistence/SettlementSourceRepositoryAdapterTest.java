package com.prompthub.admin.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import com.prompthub.admin.settlement.domain.repository.SettlementSourceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@DataJpaTest
@Import(SettlementSourceRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/source-lines.sql")
class SettlementSourceRepositoryAdapterTest {

	private static final UUID SETTLEMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private SettlementSourceRepository settlementSourceRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void 정산아이디로_묶인_소스라인을_모두_조회한다() {
		List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(SETTLEMENT_ID);

		assertThat(lines).hasSize(2);
	}

	@Test
	void 소스라인을_해제하면_정산아이디가_null이_된다() {
		List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(SETTLEMENT_ID);

		lines.forEach(line -> line.release(SETTLEMENT_ID));

		assertThat(lines).allSatisfy(line -> assertThat(line.getSettlementId()).isNull());
	}

	@Test
	void 해제_후_flush하면_dirty_checking으로_DB에_반영되어_재조회시_더이상_묶이지_않는다() {
		List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(SETTLEMENT_ID);
		lines.forEach(line -> line.release(SETTLEMENT_ID));

		entityManager.flush();
		entityManager.clear();

		List<SettlementSourceLine> reloaded = settlementSourceRepository.findBySettlementId(SETTLEMENT_ID);

		assertThat(reloaded).isEmpty();
	}
}
