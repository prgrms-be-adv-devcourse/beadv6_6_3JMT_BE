package com.prompthub.settlement.infrastructure.batch.runner;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "settlement.execution.mode", havingValue = "restart")
public class SettlementBatchRestartRunner implements ApplicationRunner, ExitCodeGenerator {

    private final RestartSettlementBatchUseCase restartSettlementBatchUseCase;
    private final UUID batchId;
    private final UUID actorId;
    private int exitCode = 1;

    public SettlementBatchRestartRunner(
            RestartSettlementBatchUseCase restartSettlementBatchUseCase,
            @Value("${settlement.restart.batch-id}") UUID batchId,
            @Value("${settlement.restart.actor-id}") UUID actorId) {
        this.restartSettlementBatchUseCase = restartSettlementBatchUseCase;
        this.batchId = batchId;
        this.actorId = actorId;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            SettlementJobResult result = restartSettlementBatchUseCase.restart(
                    new RestartSettlementBatchCommand(batchId, actorId));
            exitCode = BatchStatus.COMPLETED.name().equals(result.status()) ? 0 : 1;
            log.info(
                    "정산 배치 재시작 종료. batchId={}, actorId={}, jobExecutionId={}, status={}",
                    batchId,
                    actorId,
                    result.jobExecutionId(),
                    result.status());
        } catch (Exception exception) {
            exitCode = 1;
            log.error(
                    "정산 배치 재시작 실패. batchId={}, actorId={}",
                    batchId,
                    actorId,
                    exception);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
