package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.CreateSettlementBatchCommand;
import java.util.UUID;

public interface SettlementBatchLifecycleUseCase {

    UUID create(CreateSettlementBatchCommand command);

    void complete(UUID batchId);

    void fail(UUID batchId, String reason);

    void failReconciliation(UUID batchId, String reason);

    void requestRetry(UUID batchId);

    void startRetry(UUID batchId);

    long requireRetryJobInstanceId(UUID batchId);
}
