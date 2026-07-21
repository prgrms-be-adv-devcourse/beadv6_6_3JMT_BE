package com.prompthub.settlement.application.service;

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
public class SettlementBatchRetryStateService {

    private final SettlementBatchRepository settlementBatchRepository;

    @Transactional(readOnly = true)
    public SettlementBatch requireRetryRequested(UUID batchId) {
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
        return batch;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void restoreFailed(UUID batchId, String reason) {
        SettlementBatch batch = findBatch(batchId);
        if (!batch.isRetryRequested()) {
            return;
        }
        batch.restoreFailed(reason);
        settlementBatchRepository.save(batch);
    }

    private SettlementBatch findBatch(UUID batchId) {
        return settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND));
    }
}
