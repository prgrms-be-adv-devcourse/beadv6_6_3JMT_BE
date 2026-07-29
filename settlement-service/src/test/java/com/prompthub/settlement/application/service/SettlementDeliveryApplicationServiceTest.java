package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("수동 재전송은 누적 횟수에 추가로 최대 세 번 시도하고 같은 요청을 대사한다")
    void retriesFailedDeliveryWithThreeAdditionalAttempts() {
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.prepareManualRetry(deliveryId)).willReturn(3);
        given(transactions.beginManualRetryAttempt(deliveryId, 6)).willReturn(
                attempt(4, command), attempt(5, command), attempt(6, command));
        given(registration.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.DEADLINE_EXCEEDED, "failure"))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.success());
        given(transactions.getStatus(deliveryId))
                .willReturn(SettlementDeliveryStatus.RECONCILED);

        SettlementDeliveryStatus result = new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper)
                .retry(deliveryId);

        then(registration).should(times(3)).register(command);
        then(sleeper).should().sleep(Duration.ofSeconds(1));
        then(sleeper).should().sleep(Duration.ofSeconds(3));
        then(transactions).should().markManualRetryReconciled(deliveryId);
        assertThat(result).isEqualTo(SettlementDeliveryStatus.RECONCILED);
    }

    @Test
    @DisplayName("수동 재전송 중 예기치 않은 오류가 나면 전달 실패 상태를 복구한다")
    void restoresFailedStatusWhenManualRetryCrashes() {
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.prepareManualRetry(deliveryId)).willReturn(3);
        given(transactions.beginManualRetryAttempt(deliveryId, 6))
                .willReturn(attempt(4, command));
        given(registration.register(command))
                .willThrow(new IllegalStateException("unexpected"));

        SettlementDeliveryApplicationService service =
                new SettlementDeliveryApplicationService(
                        transactions, registration, reconciler, sleeper);

        assertThatThrownBy(() -> service.retry(deliveryId))
                .isInstanceOf(IllegalStateException.class);
        then(transactions).should().markManualRetryFailed(
                deliveryId,
                "재전송 실행 오류: IllegalStateException");
    }

    @Test
    @DisplayName("수동 재전송이 세 번 모두 통신 실패하면 전달 실패로 기록한다")
    void marksManualRetryAsFailedAfterThreeAttempts() {
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.prepareManualRetry(deliveryId)).willReturn(3);
        given(transactions.beginManualRetryAttempt(deliveryId, 6)).willReturn(
                attempt(4, command), attempt(5, command), attempt(6, command));
        given(registration.register(command))
                .willThrow(SellerSettlementDeliveryException.from(
                        Status.Code.UNAVAILABLE, "failure"));
        given(transactions.getStatus(deliveryId))
                .willReturn(SettlementDeliveryStatus.DELIVERY_FAILED);

        SettlementDeliveryStatus result = new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper)
                .retry(deliveryId);

        then(registration).should(times(3)).register(command);
        then(transactions).should().markManualRetryFailed(
                deliveryId, "gRPC UNAVAILABLE: attempts=6");
        assertThat(result).isEqualTo(SettlementDeliveryStatus.DELIVERY_FAILED);
    }

    @Test
    @DisplayName("수동 재전송 응답이 원본과 다르면 불일치로 기록한다")
    void marksManualRetryAsMismatch() {
        UUID deliveryId = UUID.randomUUID();
        SellerSettlementRegistrationCommand command =
                org.mockito.Mockito.mock(SellerSettlementRegistrationCommand.class);
        given(transactions.prepareManualRetry(deliveryId)).willReturn(3);
        given(transactions.beginManualRetryAttempt(deliveryId, 6))
                .willReturn(attempt(4, command));
        given(registration.register(command))
                .willReturn(org.mockito.Mockito.mock(
                        com.prompthub.settlement.application.dto
                                .SellerSettlementStoredSnapshot.class));
        given(reconciler.compare(any(), any()))
                .willReturn(SettlementDeliveryComparison.mismatched(
                        "settlementTotalAmount 불일치", 1));
        given(transactions.getStatus(deliveryId))
                .willReturn(SettlementDeliveryStatus.MISMATCH);

        SettlementDeliveryStatus result = new SettlementDeliveryApplicationService(
                transactions, registration, reconciler, sleeper)
                .retry(deliveryId);

        then(transactions).should().markManualRetryMismatch(
                deliveryId,
                "settlementTotalAmount 불일치, mismatchCount=1");
        assertThat(result).isEqualTo(SettlementDeliveryStatus.MISMATCH);
    }

    private Optional<SettlementDeliveryAttempt> attempt(
            int number,
            SellerSettlementRegistrationCommand command) {
        return Optional.of(new SettlementDeliveryAttempt(number, command));
    }
}
