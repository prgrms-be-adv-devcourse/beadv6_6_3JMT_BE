package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.port.SettlementJobQuery;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetSettlementJobStatusApplicationServiceTest {

    @Mock
    private SettlementJobQuery settlementJobQuery;

    @InjectMocks
    private GetSettlementJobStatusApplicationService service;

    @Test
    @DisplayName("잡 실행 이력이 있으면 상태 결과를 반환한다")
    void getStatus_found_returnsResult() {
        // given
        SettlementJobStatusResult result = new SettlementJobStatusResult(
                1L, "settlementJob", "COMPLETED", "COMPLETED",
                LocalDateTime.of(2026, 6, 3, 0, 0), LocalDateTime.of(2026, 6, 3, 0, 5), null);
        given(settlementJobQuery.findByJobExecutionId(1L)).willReturn(Optional.of(result));

        // when
        SettlementJobStatusResult found = service.getStatus(1L);

        // then
        assertThat(found.jobExecutionId()).isEqualTo(1L);
        assertThat(found.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("잡 실행 이력이 없으면 SETTLEMENT_JOB_NOT_FOUND 예외를 던진다")
    void getStatus_notFound_throwsSettlementException() {
        // given
        given(settlementJobQuery.findByJobExecutionId(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getStatus(99L))
                .isInstanceOf(SettlementException.class)
                .extracting(e -> ((SettlementException) e).getErrorCode())
                .isEqualTo(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);
    }
}
