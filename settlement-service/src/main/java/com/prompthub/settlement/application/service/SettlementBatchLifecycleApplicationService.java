package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.CreateSettlementBatchCommand;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementBatchLifecycleApplicationService implements SettlementBatchLifecycleUseCase {

    private final SettlementBatchRepository settlementBatchRepository;

    @Override
    @Transactional
    public UUID create(CreateSettlementBatchCommand command) {
        SettlementBatch batch = SettlementBatch.start(
                command.batchNo(),
                command.jobInstanceId(),
                command.period().periodStart(),
                command.period().periodEnd(),
                command.triggerType());
        return settlementBatchRepository.save(batch).getId();
    }

    @Override
    @Transactional
    public void complete(UUID batchId) {
        SettlementBatch batch = findBatch(batchId);
        batch.complete();
        settlementBatchRepository.save(batch);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID batchId, String reason) {
        SettlementBatch batch = findBatch(batchId);
        if (batch.isProcessing()) {
            batch.fail(reason);
        } else if (batch.isRetryRequested()) {
            batch.restoreFailed(reason);
        } else {
            return;
        }
        settlementBatchRepository.save(batch);
    }

    @Override
    @Transactional
    public void requestRetry(UUID batchId) {
        SettlementBatch batch = findBatch(batchId);
        batch.requestRetry();
        settlementBatchRepository.save(batch);
    }

    @Override
    @Transactional
    public void startRetry(UUID batchId) {
        SettlementBatch batch = findBatch(batchId);
        batch.startRetry();
        settlementBatchRepository.save(batch);
    }

    @Override
    @Transactional(readOnly = true)
    public long requireRetryJobInstanceId(UUID batchId) {
        SettlementBatch batch = findBatch(batchId);
        if (!batch.isRetryRequested()) {
            throw new SettlementBatchInvalidStateException(
                    SettlementBatchStatus.RETRY_REQUESTED,
                    batch.getStatus());
        }
        if (batch.getJobInstanceId() == null) {
            throw new SettlementException(
                    SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED);
        }
        return batch.getJobInstanceId();
    }

    private SettlementBatch findBatch(UUID batchId) {
        return settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND));
    }
}
