package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.domain.enums.SettlementLineType;
import com.prompthub.order.domain.repository.SettlementOrderQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SettlementOrderQueryServiceTest {

    @Mock
    private SettlementOrderQueryRepository repository;

    @InjectMocks
    private SettlementOrderQueryService service;

    @Test
    void getSettleableLines_convertsInclusiveDatesToHalfOpenRange() {
        LocalDate periodStart = LocalDate.of(2026, 7, 13);
        LocalDate periodEnd = LocalDate.of(2026, 7, 19);
        LocalDateTime startInclusive = LocalDateTime.of(2026, 7, 13, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 7, 20, 0, 0);
        SettleableLineResult line = new SettleableLineResult(
            SettlementLineType.PAID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            10_000,
            startInclusive
        );
        given(repository.findSettleableLines(startInclusive, endExclusive)).willReturn(List.of(line));

        List<SettleableLineResult> result = service.getSettleableLines(periodStart, periodEnd);

        assertThat(result).containsExactly(line);
        then(repository).should().findSettleableLines(startInclusive, endExclusive);
    }
}
