package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobRestarter;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementBatchRestartApplicationService implements RestartSettlementBatchUseCase {

    private static final String DEFAULT_FAILURE_REASON = "정산 배치 재시작 실행 실패";

    private final SettlementBatchRetryStateService retryStateService;
    private final SettlementJobRestarter settlementJobRestarter;

    @Override
    public SettlementJobResult restart(RestartSettlementBatchCommand command) {
        SettlementBatch batch = retryStateService.requireRetryRequested(command.batchId());

        try {
            return settlementJobRestarter.restart(batch.getId(), requireJobInstanceId(batch));
        } catch (RuntimeException restartFailure) {
            restoreFailed(command, restartFailure);
            throw restartFailure;
        }
    }

    private void restoreFailed(
            RestartSettlementBatchCommand command,
            RuntimeException restartFailure) {
        try {
            retryStateService.restoreFailed(command.batchId(), failureReason(restartFailure));
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

    private long requireJobInstanceId(SettlementBatch batch) {
        if (batch.getJobInstanceId() == null) {
            throw new SettlementException(
                    SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED);
        }
        return batch.getJobInstanceId();
    }
}
