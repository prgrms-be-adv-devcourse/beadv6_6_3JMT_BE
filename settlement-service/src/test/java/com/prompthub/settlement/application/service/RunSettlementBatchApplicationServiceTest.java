package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunSettlementBatchApplicationServiceTest {

    @Mock
    private SettlementJobLauncher settlementJobLauncher;

    @InjectMocks
    private RunSettlementBatchApplicationService service;

    @Test
    void 배치_실행_명령을_잡_런처에_위임한다() {
        SettlementPeriod period = SettlementPeriod.of(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        RunSettlementBatchCommand command = RunSettlementBatchCommand.scheduled(period);
        SettlementJobResult expected = new SettlementJobResult(
                42L, "settlementJob", "STARTED", LocalDateTime.of(2026, 7, 20, 0, 0));
        given(settlementJobLauncher.launch(command)).willReturn(expected);

        SettlementJobResult actual = service.run(command);

        assertThat(actual).isEqualTo(expected);
        then(settlementJobLauncher).should().launch(command);
    }
}
