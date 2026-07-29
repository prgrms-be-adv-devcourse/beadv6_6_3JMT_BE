package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SettlementDeliveryComparison;
import com.prompthub.settlement.application.dto.SettlementDeliveryAttempt;
import com.prompthub.settlement.application.exception.SellerSettlementDeliveryException;
import com.prompthub.settlement.application.port.DeliveryRetrySleeper;
import com.prompthub.settlement.application.port.SellerSettlementRegistration;
import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementDeliveryApplicationServiceTest {

    @Mock SettlementDeliveryTransactionService transactions;
    @Mock SellerSettlementRegistration registration;
    @Mock SettlementDeliveryReconciler reconciler;
    @Mock DeliveryRetrySleeper sleeper;

    @Test
    void UNAVAILABLE은_두번_재시도한_뒤_대사_완료한다() {
        UUID batchId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of(deliveryId));
        given(transactions.beginAttempt(deliveryId)).willReturn(
                attempt(1, command), attempt(2, command), attempt(3, command));
        given(registration.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.success());
        given(transactions.countByStatus(batchId)).willReturn(
                Map.of(SettlementDeliveryStatus.RECONCILED, 1L));

        new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper).deliverBatch(batchId);

        then(registration).should(times(3)).register(command);
        then(sleeper).should().sleep(Duration.ofSeconds(1));
        then(sleeper).should().sleep(Duration.ofSeconds(3));
        then(transactions).should().markReconciled(deliveryId);
    }

    @Test
    void 최종_실패해도_다음_Delivery를_처리한다() {
        UUID batchId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of(first, second));
        given(transactions.beginAttempt(first)).willReturn(attempt(1, command));
        given(transactions.beginAttempt(second)).willReturn(attempt(1, command));
        given(registration.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.INVALID_ARGUMENT, "bad request"))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.success());
        given(transactions.countByStatus(batchId)).willReturn(Map.of(
                SettlementDeliveryStatus.RECONCILED, 1L,
                SettlementDeliveryStatus.DELIVERY_FAILED, 1L));

        var result = new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper).deliverBatch(batchId);

        then(transactions).should().markFailed(
                first, "gRPC INVALID_ARGUMENT: attempts=1");
        then(transactions).should().markReconciled(second);
        assertThat(result.deliveryFailed()).isEqualTo(1);
        assertThat(result.reconciled()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.calculated()).isZero();
    }

    @Test
    void 재실행시_현재_배치의_모든_Delivery_상태를_집계한다() {
        UUID batchId = UUID.randomUUID();
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of());
        given(transactions.countByStatus(batchId)).willReturn(Map.of(
                SettlementDeliveryStatus.CALCULATED, 1L,
                SettlementDeliveryStatus.RECONCILED, 2L,
                SettlementDeliveryStatus.DELIVERY_FAILED, 3L,
                SettlementDeliveryStatus.MISMATCH, 4L));

        var result = new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper).deliverBatch(batchId);

        assertThat(result.total()).isEqualTo(10);
        assertThat(result.calculated()).isEqualTo(1);
        assertThat(result.reconciled()).isEqualTo(2);
        assertThat(result.deliveryFailed()).isEqualTo(3);
        assertThat(result.mismatch()).isEqualTo(4);
    }

    @Test
    void 재실행해도_누적_세번을_넘어_호출하지_않는다() {
        UUID batchId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of(deliveryId));
        given(transactions.beginAttempt(deliveryId)).willReturn(
                attempt(2, command), attempt(3, command));
        given(registration.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"));
        given(transactions.countByStatus(batchId)).willReturn(
                Map.of(SettlementDeliveryStatus.DELIVERY_FAILED, 1L));

        new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper).deliverBatch(batchId);

        then(registration).should(times(2)).register(command);
        then(sleeper).should().sleep(Duration.ofSeconds(3));
        then(transactions).should().markFailed(
                deliveryId, "gRPC UNAVAILABLE: attempts=3");
    }

    @Test
    void 이미_세번_시도한_CALCULATED는_원격호출하지_않는다() {
        UUID batchId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of(deliveryId));
        given(transactions.beginAttempt(deliveryId)).willReturn(Optional.empty());
        given(transactions.countByStatus(batchId)).willReturn(
                Map.of(SettlementDeliveryStatus.DELIVERY_FAILED, 1L));

        new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper).deliverBatch(batchId);

        then(registration).shouldHaveNoInteractions();
    }

    private Optional<SettlementDeliveryAttempt> attempt(
            int number,
            SellerSettlementRegistrationCommand command) {
        return Optional.of(new SettlementDeliveryAttempt(number, command));
    }
}
