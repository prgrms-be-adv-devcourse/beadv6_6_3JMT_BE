package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CompleteSettlementBatchTaskletTest {

    @Test
    @DisplayName("배치 완료 처리를 생명주기 UseCase에 위임한다")
    void execute_completesBatchThroughUseCase() throws Exception {
        UUID batchId = UUID.randomUUID();
        SettlementBatchLifecycleUseCase lifecycleUseCase =
                mock(SettlementBatchLifecycleUseCase.class);
        CompleteSettlementBatchTasklet tasklet =
                new CompleteSettlementBatchTasklet(lifecycleUseCase);
        ReflectionTestUtils.setField(
                tasklet,
                "settlementBatchIdParam",
                batchId.toString());

        tasklet.execute(null, null);

        then(lifecycleUseCase).should().complete(batchId);
    }
}
