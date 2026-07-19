package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;

class LoadSettlementSourceTaskletTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

    @Test
    void execute_loadsTheInclusiveWeeklyPeriod() throws Exception {
        LoadSettlementSourceUseCase useCase = mock(LoadSettlementSourceUseCase.class);
        LoadSettlementSourceTasklet tasklet = new LoadSettlementSourceTasklet(useCase);
        ReflectionTestUtils.setField(tasklet, "periodStartParam", "2026-07-13");
        ReflectionTestUtils.setField(tasklet, "periodEndParam", "2026-07-19");

        RepeatStatus result = tasklet.execute(null, null);

        then(useCase).should().load(PERIOD);
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    }
}
