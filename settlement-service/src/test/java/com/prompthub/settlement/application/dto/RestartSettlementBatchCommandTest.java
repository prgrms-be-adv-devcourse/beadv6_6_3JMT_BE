package com.prompthub.settlement.application.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestartSettlementBatchCommandTest {

    @Test
    @DisplayName("재시작 command에는 배치 ID와 관리자 ID가 필요하다")
    void create_missingIdentifier_throwsException() {
        UUID batchId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> new RestartSettlementBatchCommand(null, actorId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("batchId");
        assertThatThrownBy(() -> new RestartSettlementBatchCommand(batchId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorId");
    }
}
