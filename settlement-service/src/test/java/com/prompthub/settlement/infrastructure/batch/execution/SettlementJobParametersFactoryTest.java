package com.prompthub.settlement.infrastructure.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameters;

class SettlementJobParametersFactoryTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000364");
    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

    private final SettlementJobParametersFactory factory = new SettlementJobParametersFactory();

    @Test
    void create_usesOnlyPeriodDatesAsIdentifyingParameters() {
        JobParameters parameters = factory.create(
                RunSettlementBatchCommand.manual(PERIOD, ACTOR_ID));

        assertThat(parameters.getString("periodStart")).isEqualTo("2026-07-13");
        assertThat(parameters.getString("periodEnd")).isEqualTo("2026-07-19");
        assertThat(parameters.getParameter("periodStart").identifying()).isTrue();
        assertThat(parameters.getParameter("periodEnd").identifying()).isTrue();
        assertThat(parameters.getParameter("requestedAt").identifying()).isFalse();
        assertThat(parameters.getParameter("actorId").identifying()).isFalse();
        assertThat(parameters.getParameter("triggerType").identifying()).isFalse();
    }

    @Test
    void create_omitsActorIdForScheduledExecution() {
        JobParameters parameters = factory.create(RunSettlementBatchCommand.scheduled(PERIOD));

        assertThat(parameters.getParameter("actorId")).isNull();
        assertThat(parameters.getString("triggerType")).isEqualTo("SCHEDULED");
    }
}
