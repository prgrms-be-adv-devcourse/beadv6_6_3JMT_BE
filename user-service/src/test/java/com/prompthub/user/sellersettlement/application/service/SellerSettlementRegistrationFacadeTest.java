package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRegistrationFacadeTest {

    @Mock
    private SellerSettlementRegistrationApplicationService writer;

    @Mock
    private SellerSettlementReadBackApplicationService reader;

    @Test
    @DisplayName("등록 트랜잭션이 끝난 뒤 DB를 다시 읽은 Snapshot을 반환한다")
    void returnsReadBackSnapshotAfterRegistration() {
        RegisterSellerSettlementCommand command = command();
        RegisteredSellerSettlementSnapshot stored = snapshot(command);
        given(writer.register(command)).willReturn(command.settlementId());
        given(reader.readBySettlementId(command.settlementId())).willReturn(stored);
        SellerSettlementRegistrationFacade facade =
                new SellerSettlementRegistrationFacade(writer, reader);

        RegisteredSellerSettlementSnapshot actual = facade.register(command);

        assertThat(actual).isSameAs(stored);
    }

    private RegisterSellerSettlementCommand command() {
        return new RegisterSellerSettlementCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 7),
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDateTime.of(2026, 7, 8, 2, 0),
                List.of());
    }

    private RegisteredSellerSettlementSnapshot snapshot(
            RegisterSellerSettlementCommand command) {
        return new RegisteredSellerSettlementSnapshot(
                command.deliveryRequestId(),
                command.settlementId(),
                command.sellerId(),
                command.periodStart(),
                command.periodEnd(),
                command.productCount(),
                command.grossSalesAmount(),
                command.refundAmount(),
                command.feeTotalAmount(),
                command.settlementTotalAmount(),
                command.calculatedAt(),
                List.of());
    }
}
