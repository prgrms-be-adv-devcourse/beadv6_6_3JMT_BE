package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    @Test
    @DisplayName("동시 중복 등록이면 deliveryRequestId로 이미 커밋된 결과를 반환한다")
    void recoversKnownDeliveryRequestConflict() {
        RegisterSellerSettlementCommand command = command();
        RegisteredSellerSettlementSnapshot stored = snapshot(command);
        given(writer.register(command))
                .willThrow(new DataIntegrityViolationException("unique conflict"));
        given(reader.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.of(stored));
        SellerSettlementRegistrationFacade facade =
                new SellerSettlementRegistrationFacade(writer, reader);

        RegisteredSellerSettlementSnapshot actual = facade.register(command);

        assertThat(actual).isSameAs(stored);
    }

    @Test
    @DisplayName("등록 대상이 없는 무결성 오류는 멱등 충돌로 숨기지 않는다")
    void propagatesUnrelatedIntegrityViolation() {
        RegisterSellerSettlementCommand command = command();
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("not-null violation");
        given(writer.register(command)).willThrow(failure);
        given(reader.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.empty());
        SellerSettlementRegistrationFacade facade =
                new SellerSettlementRegistrationFacade(writer, reader);

        assertThatThrownBy(() -> facade.register(command)).isSameAs(failure);
    }

    @Test
    @DisplayName("deliveryRequestId가 없으면 settlementId를 충돌 복구 조건으로 사용하지 않는다")
    void doesNotRecoverConflictBySettlementId() {
        RegisterSellerSettlementCommand command = command();
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated constraint violation");
        given(writer.register(command)).willThrow(failure);
        given(reader.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.empty());
        SellerSettlementRegistrationFacade facade =
                new SellerSettlementRegistrationFacade(writer, reader);

        assertThatThrownBy(() -> facade.register(command)).isSameAs(failure);
        then(reader).should(never()).findBySettlementId(command.settlementId());
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
