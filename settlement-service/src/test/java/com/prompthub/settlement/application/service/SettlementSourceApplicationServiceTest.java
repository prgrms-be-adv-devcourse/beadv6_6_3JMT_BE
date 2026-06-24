package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.RecordSettlementSourceCommand;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSourceApplicationServiceTest {

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @InjectMocks
    private SettlementSourceApplicationService service;

    private RecordSettlementSourceCommand command(UUID eventId, SettlementSourceEventType eventType) {
        return new RecordSettlementSourceCommand(eventId, eventType, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("9900"), LocalDateTime.of(2026, 6, 15, 10, 0));
    }

    @Test
    @DisplayName("새 이벤트면 PAID 소스 라인을 저장한다")
    void record_newPaid_saves() {
        UUID eventId = UUID.randomUUID();
        RecordSettlementSourceCommand command = command(eventId, SettlementSourceEventType.PAID);
        given(settlementSourceRepository.existsByEventId(eventId)).willReturn(false);

        service.record(command);

        ArgumentCaptor<SettlementSourceLine> captor = ArgumentCaptor.forClass(SettlementSourceLine.class);
        verify(settlementSourceRepository).save(captor.capture());
        SettlementSourceLine saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getEventType()).isEqualTo(SettlementSourceEventType.PAID);
        assertThat(saved.getLineAmount()).isEqualByComparingTo("9900");
    }

    @Test
    @DisplayName("REFUND 커맨드는 REFUND 소스 라인으로 저장된다")
    void record_refund_savesRefundLine() {
        UUID eventId = UUID.randomUUID();
        given(settlementSourceRepository.existsByEventId(eventId)).willReturn(false);

        service.record(command(eventId, SettlementSourceEventType.REFUND));

        ArgumentCaptor<SettlementSourceLine> captor = ArgumentCaptor.forClass(SettlementSourceLine.class);
        verify(settlementSourceRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(SettlementSourceEventType.REFUND);
    }

    @Test
    @DisplayName("이미 적재된 eventId면 저장하지 않는다(멱등)")
    void record_duplicate_skipsSave() {
        UUID eventId = UUID.randomUUID();
        given(settlementSourceRepository.existsByEventId(eventId)).willReturn(true);

        service.record(command(eventId, SettlementSourceEventType.PAID));

        verify(settlementSourceRepository, never()).save(any());
    }
}
