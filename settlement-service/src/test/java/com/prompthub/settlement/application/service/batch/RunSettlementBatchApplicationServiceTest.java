package com.prompthub.settlement.application.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.batch.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;
import com.prompthub.settlement.application.usecase.batch.SettlementJobLauncher;
import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunSettlementBatchApplicationServiceTest {

    @Test
    @DisplayName("배치 실행 요청을 Job 실행 포트에 위임한다")
    void run_delegatesToLauncher() {
        SettlementJobLauncher jobLauncher = mock(SettlementJobLauncher.class);
        RunSettlementBatchApplicationService service =
                new RunSettlementBatchApplicationService(jobLauncher);
        RunSettlementBatchCommand command = RunSettlementBatchCommand.scheduled(
                SettlementPeriod.of(
                        LocalDate.of(2026, 7, 13),
                        LocalDate.of(2026, 7, 19)));
        SettlementJobResult expected = new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0));
        given(jobLauncher.launch(command)).willReturn(expected);

        assertThat(service.run(command)).isEqualTo(expected);
    }
}
