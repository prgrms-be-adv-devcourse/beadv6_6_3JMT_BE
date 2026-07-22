package com.prompthub.settlement.infrastructure.batch.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

class SettlementStepConfigTest {

    @Test
    @DisplayName("정산 청크 크기는 1 이상이어야 한다")
    void create_nonPositiveChunkSize_throwsException() {
        assertThatThrownBy(() -> new SettlementStepConfig(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk-size");
    }
}
