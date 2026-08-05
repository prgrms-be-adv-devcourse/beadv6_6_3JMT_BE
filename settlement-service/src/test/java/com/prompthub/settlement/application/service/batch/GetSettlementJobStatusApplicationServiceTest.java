package com.prompthub.settlement.application.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.batch.SettlementJobStatusResult;
import com.prompthub.settlement.application.usecase.batch.SettlementJobQuery;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetSettlementJobStatusApplicationServiceTest {

    @Test
    @DisplayName("배치 실행 상태를 Job 조회 포트에서 반환한다")
    void getStatus_existingExecution_returnsStatus() {
        SettlementJobQuery jobQuery = mock(SettlementJobQuery.class);
        GetSettlementJobStatusApplicationService service =
                new GetSettlementJobStatusApplicationService(jobQuery);
        SettlementJobStatusResult expected = new SettlementJobStatusResult(
                101L,
                "settlementJob",
                "COMPLETED",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0),
                LocalDateTime.of(2026, 7, 21, 10, 1),
                null);
        given(jobQuery.findByJobExecutionId(101L)).willReturn(Optional.of(expected));

        assertThat(service.getStatus(101L)).isEqualTo(expected);
    }

    @Test
    @DisplayName("배치 실행 상태가 없으면 Job 없음 오류를 던진다")
    void getStatus_missingExecution_throwsException() {
        SettlementJobQuery jobQuery = mock(SettlementJobQuery.class);
        GetSettlementJobStatusApplicationService service =
                new GetSettlementJobStatusApplicationService(jobQuery);
        given(jobQuery.findByJobExecutionId(101L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(101L))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }
}
