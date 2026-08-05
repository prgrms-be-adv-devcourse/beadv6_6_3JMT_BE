package com.prompthub.settlement.application.service.batch;

import com.prompthub.settlement.application.dto.batch.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;
import com.prompthub.settlement.application.usecase.batch.RestartSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.application.usecase.batch.SettlementJobRestarter;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestartSettlementBatchApplicationService
        implements RestartSettlementBatchUseCase {

    private static final String DEFAULT_FAILURE_REASON = "정산 배치 재시작 실행 실패";

    private final SettlementJobRestarter settlementJobRestarter;
    private final SettlementBatchLifecycleUseCase settlementBatchLifecycleUseCase;

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

    private long requireRetryJobInstanceId(RestartSettlementBatchCommand command) {
        try {
            return settlementBatchLifecycleUseCase.requireRetryJobInstanceId(
                    command.batchId());
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
