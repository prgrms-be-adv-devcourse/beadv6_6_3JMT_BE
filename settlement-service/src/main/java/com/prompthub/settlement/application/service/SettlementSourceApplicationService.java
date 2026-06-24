package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.RecordSettlementSourceCommand;
import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSourceApplicationService implements SettlementSourceUseCase {

    private final SettlementSourceRepository settlementSourceRepository;

    @Override
    @Transactional
    public void record(RecordSettlementSourceCommand command) {
        if (settlementSourceRepository.existsByEventId(command.eventId())) {
            log.debug("이미 적재된 정산 소스 라인이라 건너뜁니다. eventId={}", command.eventId());
            return;
        }
        settlementSourceRepository.save(toSourceLine(command));
    }

    private SettlementSourceLine toSourceLine(RecordSettlementSourceCommand command) {
        return switch (command.eventType()) {
            case PAID -> SettlementSourceLine.paid(
                    command.eventId(),
                    command.orderId(),
                    command.orderProductId(),
                    command.sellerId(),
                    command.amount(),
                    command.occurredAt());
            case REFUND -> SettlementSourceLine.refunded(
                    command.eventId(),
                    command.orderId(),
                    command.orderProductId(),
                    command.sellerId(),
                    command.amount(),
                    command.occurredAt());
        };
    }
}
