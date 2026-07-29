package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SettlementDeliveryComparison;
import com.prompthub.settlement.application.exception.SellerSettlementDeliveryException;
import com.prompthub.settlement.application.port.SellerSettlementRegistrationPort;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementDeliveryApplicationServiceTest {

    @Mock SettlementDeliveryTransactionService transactions;
    @Mock SellerSettlementRegistrationPort port;
    @Mock SettlementDeliveryReconciler reconciler;
    @Mock DeliveryRetrySleeper sleeper;

    @Test
    void UNAVAILABLE은_두번_재시도한_뒤_대사_완료한다() {
        UUID batchId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.findCalculatedIds(batchId)).willReturn(List.of(deliveryId));
        given(transactions.beginAttempt(deliveryId)).willReturn(command);
        given(port.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.success());

        new SettlementDeliveryApplicationService(
                transactions, port, reconciler, sleeper).deliverBatch(batchId);

        then(port).should(times(3)).register(command);
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
        given(transactions.beginAttempt(any())).willReturn(command);
        given(port.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.INVALID_ARGUMENT, "bad request"))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.success());

        var result = new SettlementDeliveryApplicationService(
                transactions, port, reconciler, sleeper).deliverBatch(batchId);

        then(transactions).should().markFailed(
                first, "gRPC INVALID_ARGUMENT: attempts=1");
        then(transactions).should().markReconciled(second);
        assertThat(result.deliveryFailed()).isEqualTo(1);
        assertThat(result.reconciled()).isEqualTo(1);
    }
}
