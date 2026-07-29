package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.application.port.SettlementJobQuery;
import com.prompthub.settlement.application.port.SettlementJobRestarter;
import com.prompthub.settlement.application.usecase.GetSettlementJobStatusUseCase;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementBatchExecutionApplicationService
        implements RunSettlementBatchUseCase,
        RestartSettlementBatchUseCase,
        GetSettlementJobStatusUseCase {

    private static final String DEFAULT_FAILURE_REASON = "정산 배치 재시작 실행 실패";

    private final SettlementJobLauncher settlementJobLauncher;
    private final SettlementJobRestarter settlementJobRestarter;
    private final SettlementJobQuery settlementJobQuery;
    private final SettlementBatchLifecycleUseCase settlementBatchLifecycleUseCase;

    @Override
    public SettlementJobResult run(RunSettlementBatchCommand command) {
        return settlementJobLauncher.launch(command);
    }

    @Override
    public SettlementJobResult restart(RestartSettlementBatchCommand command) {
        long jobInstanceId = requireRetryJobInstanceId(command);
        try {
            return settlementJobRestarter.restart(command.batchId(), jobInstanceId);
        } catch (RuntimeException restartFailure) {
            restoreFailed(command, restartFailure);
            throw restartFailure;
        }
    }

    @Override
    public SettlementJobStatusResult getStatus(Long jobExecutionId) {
        return settlementJobQuery.findByJobExecutionId(jobExecutionId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }

    private long requireRetryJobInstanceId(RestartSettlementBatchCommand command) {
        try {
            return settlementBatchLifecycleUseCase.requireRetryJobInstanceId(command.batchId());
        } catch (SettlementException exception) {
            if (exception.getErrorCode()
                    == SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED) {
                restoreFailed(command, exception);
            }
            throw exception;
        }
    }

    private void restoreFailed(
            RestartSettlementBatchCommand command,
            RuntimeException restartFailure) {
        try {
            settlementBatchLifecycleUseCase.fail(
                    command.batchId(),
                    failureReason(restartFailure));
        } catch (RuntimeException restoreFailure) {
            if (restoreFailure != restartFailure) {
                restartFailure.addSuppressed(restoreFailure);
            }
            log.error(
                    "정산 배치 재시작 상태 복원 실패. batchId={}, actorId={}",
                    command.batchId(),
                    command.actorId(),
                    restoreFailure);
        }
    }

    private String failureReason(RuntimeException failure) {
        if (failure.getMessage() == null || failure.getMessage().isBlank()) {
            return DEFAULT_FAILURE_REASON;
        }
        return failure.getMessage();
    }
}
