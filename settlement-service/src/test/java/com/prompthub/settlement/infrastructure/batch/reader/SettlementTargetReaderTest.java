package com.prompthub.settlement.infrastructure.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.infrastructure.batch.model.SettlementTarget;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementTargetReaderTest {

    private static final UUID SELLER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

    @Test
    void read_queriesSellersAndCreatesItemWithTheSameWeeklyPeriod() {
        SettlementSourceRepository repository = mock(SettlementSourceRepository.class);
        given(repository.findSettleableSellerIds(PERIOD)).willReturn(List.of(SELLER_ID));
        SettlementTargetReader reader = new SettlementTargetReader(repository);
        ReflectionTestUtils.setField(reader, "periodStartParam", "2026-07-13");
        ReflectionTestUtils.setField(reader, "periodEndParam", "2026-07-19");
        ReflectionTestUtils.setField(reader, "settlementBatchIdParam", BATCH_ID.toString());

        SettlementTarget first = reader.read();
        SettlementTarget exhausted = reader.read();

        assertThat(first).isEqualTo(new SettlementTarget(SELLER_ID, PERIOD, BATCH_ID));
        assertThat(exhausted).isNull();
        then(repository).should().findSettleableSellerIds(PERIOD);
    }
}
